package com.trophonix.tradeplus.logging;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

class NullEmptyListAdapter implements JsonSerializer<List<?>>, JsonDeserializer<List<?>> {

	@Override
	public JsonElement serialize(List<?> src, Type type, JsonSerializationContext context) {
		if (src == null || src.isEmpty()) {
			return null;
		}
		JsonArray array = new JsonArray();
		for (Object obj : src) {
			array.add(context.serialize(obj));
		}
		return array;
	}

	@Override
	public List<?> deserialize(JsonElement src, Type type,
							   JsonDeserializationContext context) throws JsonParseException {
		List<?> list = new ArrayList<>();
		if (src == null) {
			return list;
		}
		if (!(src instanceof JsonArray)) {
			throw new JsonParseException("Invalid list");
		}
		for (JsonElement elem : (JsonArray) src) {
			list.add(context.deserialize(elem, type));
		}
		return list;
	}

}
