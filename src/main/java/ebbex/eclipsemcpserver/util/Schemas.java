package ebbex.eclipsemcpserver.util;

import java.util.List;
import java.util.Map;

/** Fragments for building tool input schemas (JSON Schema 2020-12 as plain maps). */
public final class Schemas {

	private Schemas() {
	}

	/** Schema fragment for a string property with a description. */
	public static Map<String, Object> stringProp(String description) {
		return Map.of("type", "string", "description", description);
	}

	/** Schema fragment for an integer property with a description. */
	public static Map<String, Object> intProp(String description) {
		return Map.of("type", "integer", "description", description);
	}

	/** Schema fragment for a boolean property with a description. */
	public static Map<String, Object> boolProp(String description) {
		return Map.of("type", "boolean", "description", description);
	}

	/** Schema fragment for a string enum property. */
	public static Map<String, Object> enumProp(String description, List<String> values) {
		return Map.of("type", "string", "description", description, "enum", values);
	}
}
