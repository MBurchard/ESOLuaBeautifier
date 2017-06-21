package de.mbur.jlua;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Martin Burchard
 *
 */
public class LuaTableParser {
	private static final String STRING_LUA_NIL_CONSTANT = "LUA_NIL_CONSTANT";
	private static final String STRING_NEW_LINE = "\n";
	private static final String STRING_INDENT = "    ";

	private enum STATES {
		ASSIGNMENT, BEFORE_ASSIGNMENT, MAP, MAP_KEY, ROOT, NUMBER_VALUE, STRING_VALUE,
		STRING_VALUE_ESCAPED, VALUE, VAR_NAME, COMMENT
	}

	private static final Logger log = LoggerFactory.getLogger(LuaTableParser.class);
	private static Pattern PATTERN_KEY_NUM = Pattern.compile("^(\\d+)$");
	private static Pattern PATTERN_KEY_STRING = Pattern.compile("^\"(.+)\"$");

	private static int globalCompare(final Object o1, final Object o2) {
		if (o1 != null && o1.equals(o2)) {
			return 0;
		}
		if (o1 != null && o2 != null) {
			if (o1 instanceof String && o2 instanceof String) {
				final String s1 = (String) o1;
				return s1.compareToIgnoreCase((String) o2);
			}
		}
		if (o1 instanceof Integer && o2 instanceof String) {
			return -1;
		}
		if (o1 instanceof String && o2 instanceof Integer) {
			return 1;
		}
		if (o1 instanceof Integer && o2 instanceof Integer) {
			final Integer i1 = (Integer) o1;
			return i1.compareTo((Integer) o2);
		}
		return 0;
	}

	private final StringBuilder buf = new StringBuilder();
	private Object currentKey;
	private Charset encoding = Charset.defaultCharset();
	private final Stack<Object> pointer = new Stack<>();
	private STATES state = STATES.ROOT;
	private Map<Object, Object> variables = getMap();
	private String previousChar;

	private void addToMap(final Object value) {
		if (currentKey != null) {
			final Map<Object, Object> map = (Map<Object, Object>) pointer.peek();
			if (map != null) {
				map.put(currentKey, value);
				state = STATES.MAP;
			}
		}
	}

	private static Map<Object, Object> getMap() {
		return new TreeMap<>((o1, o2) -> globalCompare(o1, o2));
		// return new LinkedHashMap<>();
	}

	public Charset getEncoding() {
		return encoding;
	}

	private void handle(final String s) {
		if (state == STATES.ROOT) {
			if (s.matches("\\w")) {
				state = STATES.VAR_NAME;
				buf.setLength(0);
				buf.append(s);
			}
		} else if (state == STATES.VAR_NAME) {
			if (s.matches("\\w")) {
				buf.append(s);
			} else {
				state = STATES.BEFORE_ASSIGNMENT;
				currentKey = buf.toString();
				log.debug("global var name: {}", currentKey);
			}
		} else if (state == STATES.BEFORE_ASSIGNMENT) {
			if (s.matches("\\s")) {
				// ignore whitespace
			} else if (s.matches("=")) {
				state = STATES.ASSIGNMENT;
			}
		} else if (state == STATES.ASSIGNMENT) {
			if (s.matches("\\s")) {
				// ignore whitespace
			} else if (s.matches("\\{")) {
				// map assignment
				final Map<Object, Object> subTree = getMap();
				addToMap(subTree);
				pointer.push(subTree);
			} else if (s.matches("\"")) {
				state = STATES.STRING_VALUE;
				buf.setLength(0);
			} else if (s.matches("\\d|-")) {
				state = STATES.NUMBER_VALUE;
				buf.setLength(0);
				buf.append(s);
			} else {
				state = STATES.VALUE;
				buf.setLength(0);
				buf.append(s);
			}
		} else if (state == STATES.VALUE) {
			if (s.matches(",|\\s")) {
				if ("true".equalsIgnoreCase(buf.toString())) {
					addToMap(true);
				} else if ("false".equalsIgnoreCase(buf.toString())) {
					addToMap(false);
				} else if ("nil".equalsIgnoreCase(buf.toString())) {
					addToMap(STRING_LUA_NIL_CONSTANT);
				}
			} else {
				buf.append(s);
			}
		} else if (state == STATES.NUMBER_VALUE) {
			if (s.matches("\\d|\\.")) {
				buf.append(s);
			} else {
				if (buf.indexOf(".") > -1) {
					addToMap(Double.parseDouble(buf.toString()));
				} else {
					addToMap(Long.parseLong(buf.toString(), 10));
				}
			}
		} else if (state == STATES.STRING_VALUE) {
			if (s.matches("\\\\")) {
				state = STATES.STRING_VALUE_ESCAPED;
			} else if (s.matches("\"")) {
				addToMap(buf.toString());
			} else {
				buf.append(s);
			}
		} else if (state == STATES.STRING_VALUE_ESCAPED) {
			if (!s.equals("\\")) {
				buf.append("\\");
			}
			buf.append(s);
			state = STATES.STRING_VALUE;
		} else if (state == STATES.COMMENT) {
			// ignore until new line
			if (s.matches("\n")) {
				state = STATES.MAP;
				previousChar = null;
			}
		} else if (state == STATES.MAP) {
			if (s.matches("\\s")) {
				// ignore whitespace
			} else if (s.matches("-")) {
				if ("-".equals(previousChar)) {
					state = STATES.COMMENT;
				} else {
					previousChar = s;
				}
			} else if (s.matches("\\[")) {
				state = STATES.MAP_KEY;
				buf.setLength(0);
				currentKey = null;
			} else if (s.matches("\\}")) {
				pointer.pop();
				if (pointer.size() == 1) {
					state = STATES.ROOT;
				}
			}
		} else if (state == STATES.MAP_KEY) {
			if (s.matches("\\]")) {
				state = STATES.BEFORE_ASSIGNMENT;
				Matcher m = PATTERN_KEY_STRING.matcher(buf.toString());
				if (m.matches()) {
					currentKey = m.group(1);
				} else {
					m = PATTERN_KEY_NUM.matcher(buf.toString());
					if (m.matches()) {
						currentKey = Integer.parseInt(m.group(1), 10);
					}
				}
			} else {
				buf.append(s);
			}
		}
	}

	public void parse(final InputStream in) throws IOException {
		parse(new BufferedReader(new InputStreamReader(in, encoding)));
	}

	public void parse(final Reader reader) throws IOException {
		pointer.push(variables);
		int r;
		while ((r = reader.read()) != -1) {
			handle(String.valueOf((char) r));
		}
	}

	public void setEncoding(final Charset encoding) {
		this.encoding = encoding;
	}

	public void setEncoding(final String encoding) {
		this.encoding = Charset.forName(encoding);
	}

	public void writeTo(OutputStream out) throws IOException {
		try (Writer writer = new PrintWriter(out)) {
			for (Object key : variables.keySet()) {
				if (key instanceof String) {
					writer.write(key + " =");
					writeMap(writer, (Map<Object, Object>) variables.get(key), "");
					writer.write(STRING_NEW_LINE);
				}
			}
		}
	}

	private void writeMap(Writer writer, Map<Object, Object> map, String indent) throws IOException {
		writer.write(STRING_NEW_LINE + indent + "{");
		String subIndent = indent + STRING_INDENT;
		for (Object key : map.keySet()) {
			writer.write(STRING_NEW_LINE + subIndent + "[");
			if (key instanceof Integer) {
				writer.write("" + key);
			} else {
				writer.write("\"" + key + "\"");
			}
			writer.write("] = ");
			Object currentValue = map.get(key);
			if (currentValue instanceof Map) {
				writeMap(writer, (Map<Object, Object>) currentValue, subIndent);
			} else if (currentValue instanceof String) {
				if (STRING_LUA_NIL_CONSTANT.equals(currentValue)) {
					writer.write("nil");
				} else {
					writer.write("\"" + ((String) currentValue).replaceAll("\"", "\\\\\"") + "\"");
				}
			} else if (currentValue instanceof Long) {
				writer.write("" + currentValue);
			} else if (currentValue instanceof Double) {
				writer.write(String.format(Locale.ENGLISH, "%.10f", currentValue));
			} else if (currentValue instanceof Boolean) {
				writer.write((boolean) currentValue ? "true" : "false");
			}
			writer.write(",");
		}
		writer.write(STRING_NEW_LINE + indent + "}");
	}

	/**
	 * @return the variables
	 */
	public Map<Object, Object> getVariables() {
		return variables;
	}

}
