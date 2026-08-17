package xyz.pyxismc.tournament.common.message;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * JSON serialization of the Redis protocol messages. Records serialize
 * through Gson field names, so Velocity and Paper must agree on the record
 * definitions (they share the same classes from tournament-common).
 *
 * <p>{@link Duration} and {@link Instant} cannot be reflected by Gson
 * (java.time module): they are exchanged as milliseconds since the epoch.</p>
 */
public final class JsonCodec {

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Duration.class, new DurationAdapter())
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();

    public String toJson(Object value) {
        return this.gson.toJson(value);
    }

    public <T> T fromJson(String json, Class<T> type) {
        return this.gson.fromJson(json, type);
    }

    private static final class DurationAdapter
            implements JsonSerializer<Duration>, JsonDeserializer<Duration> {

        @Override
        public JsonElement serialize(Duration duration, Type type, JsonSerializationContext context) {
            return new JsonPrimitive(duration.toMillis());
        }

        @Override
        public Duration deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            return Duration.ofMillis(json.getAsLong());
        }
    }

    private static final class InstantAdapter
            implements JsonSerializer<Instant>, JsonDeserializer<Instant> {

        @Override
        public JsonElement serialize(Instant instant, Type type, JsonSerializationContext context) {
            return new JsonPrimitive(instant.toEpochMilli());
        }

        @Override
        public Instant deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            return Instant.ofEpochMilli(json.getAsLong());
        }
    }
}