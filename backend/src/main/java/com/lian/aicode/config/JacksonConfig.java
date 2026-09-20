package com.lian.aicode.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** 将雪花 Long ID 序列化为字符串，避免 JavaScript Number 丢失 64 位整数精度。 */
@Configuration
public class JacksonConfig {

    @Bean
    public Module longAsStringModule() {
        SimpleModule module = new SimpleModule("long-as-string");
        module.addSerializer(Long.class, new LongToStringSerializer());
        module.addSerializer(Long.TYPE, new LongToStringSerializer());
        return module;
    }

    private static final class LongToStringSerializer extends StdSerializer<Long> {

        private LongToStringSerializer() {
            super(Long.class);
        }

        @Override
        public void serialize(Long value, JsonGenerator generator, com.fasterxml.jackson.databind.SerializerProvider provider)
                throws IOException {
            generator.writeString(value.toString());
        }
    }
}
