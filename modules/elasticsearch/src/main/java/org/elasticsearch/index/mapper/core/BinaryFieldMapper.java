/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper.core;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeBooleanValue;
import static org.elasticsearch.index.mapper.MapperBuilders.*;
import static org.elasticsearch.index.mapper.core.TypeParsers.*;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.Fieldable;
import org.elasticsearch.ElasticSearchParseException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.lzf.LZF;
import org.elasticsearch.common.compress.lzf.LZFDecoder;
import org.elasticsearch.common.io.stream.CachedStreamOutput;
import org.elasticsearch.common.io.stream.LZFStreamOutput;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MergeContext;
import org.elasticsearch.index.mapper.MergeMappingException;
import org.elasticsearch.index.mapper.ParseContext;

/**
 * @author kimchy (shay.banon)
 */
public class BinaryFieldMapper extends AbstractFieldMapper<byte[]> {

    public static final String CONTENT_TYPE = "binary";

    public static class Defaults extends AbstractFieldMapper.Defaults {
        public static final long COMPRESS_THRESHOLD = -1;
    }

    public static class Builder extends AbstractFieldMapper.Builder<Builder, BinaryFieldMapper> {

        private Boolean compress = null;

        private long compressThreshold = Defaults.COMPRESS_THRESHOLD;

        public Builder(String name) {
            super(name);
            builder = this;
        }

        public Builder compress(boolean compress) {
            this.compress = compress;
            return this;
        }

        public Builder compressThreshold(long compressThreshold) {
            this.compressThreshold = compressThreshold;
            return this;
        }

        @Override public Builder indexName(String indexName) {
            return super.indexName(indexName);
        }

        @Override public BinaryFieldMapper build(BuilderContext context) {
            return new BinaryFieldMapper(buildNames(context), compress, compressThreshold);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            BinaryFieldMapper.Builder builder = binaryField(name);
            parseField(builder, name, node, parserContext);
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();
                if (fieldName.equals("compress") && fieldNode != null) {
                    builder.compress(nodeBooleanValue(fieldNode));
                } else if (fieldName.equals("compress_threshold") && fieldNode != null) {
                    if (fieldNode instanceof Number) {
                        builder.compressThreshold(((Number) fieldNode).longValue());
                        builder.compress(true);
                    } else {
                        builder.compressThreshold(ByteSizeValue.parseBytesSizeValue(fieldNode.toString()).bytes());
                        builder.compress(true);
                    }
                }
            }
            return builder;
        }
    }

    private Boolean compress;

    private long compressThreshold;

    protected BinaryFieldMapper(Names names, Boolean compress, long compressThreshold) {
        super(names, Field.Index.NO, Field.Store.YES, Field.TermVector.NO, 1.0f, true, true, null, null);
        this.compress = compress;
        this.compressThreshold = compressThreshold;
    }

    @Override
    public Object valueForSearch(Fieldable field) {
        return value(field);
    }

    @Override public byte[] value(Fieldable field) {
        byte[] value = field.getBinaryValue();
        if (value != null && LZF.isCompressed(value)) {
            try {
                return LZFDecoder.decode(value);
            } catch (IOException e) {
                throw new ElasticSearchParseException("failed to decompress source", e);
            }
        }
        return value;
    }

    @Override public byte[] valueFromString(String value) {
        return null;
    }

    @Override public String valueAsString(Fieldable field) {
        return null;
    }

    @Override public String indexedValue(String value) {
        return value;
    }

    @Override protected Field parseCreateField(ParseContext context) throws IOException {
        byte[] value;
        if (context.parser().currentToken() == XContentParser.Token.VALUE_NULL) {
            return null;
        } else {
            value = context.parser().binaryValue();
            if (compress != null && compress && !LZF.isCompressed(value, 0, value.length)) {
                if (compressThreshold == -1 || value.length > compressThreshold) {
                    CachedStreamOutput.Entry cachedEntry = CachedStreamOutput.popEntry();
                    LZFStreamOutput streamOutput = cachedEntry.cachedLZFBytes();
                    streamOutput.writeBytes(value, 0, value.length);
                    streamOutput.flush();
                    // we copy over the byte array, since we need to push back the cached entry
                    // TODO, we we had a handle into when we are done with parsing, then we push back then and not copy over bytes
                    value = cachedEntry.bytes().copiedByteArray();
                    CachedStreamOutput.pushEntry(cachedEntry);
                    // update the data in the context, so it can be compressed and stored compressed outside...
                    context.source(value, 0, value.length);
                }
            }
        }
        if (value == null) {
            return null;
        }
        return new Field(names.indexName(), value);
    }

    @Override protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(names.name());
        builder.field("type", contentType());
        if (!names.name().equals(names.indexNameClean())) {
            builder.field("index_name", names.indexNameClean());
        }
        if (compress != null) {
            builder.field("compress", compress);
        }
        if (compressThreshold != -1) {
            builder.field("compress_threshold", new ByteSizeValue(compressThreshold).toString());
        }
        builder.endObject();
        return builder;
    }

    @Override public void merge(Mapper mergeWith, MergeContext mergeContext) throws MergeMappingException {
        BinaryFieldMapper sourceMergeWith = (BinaryFieldMapper) mergeWith;
        if (!mergeContext.mergeFlags().simulate()) {
            if (sourceMergeWith.compress != null) {
                this.compress = sourceMergeWith.compress;
            }
            if (sourceMergeWith.compressThreshold != -1) {
                this.compressThreshold = sourceMergeWith.compressThreshold;
            }
        }
    }

}