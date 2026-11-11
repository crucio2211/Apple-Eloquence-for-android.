package org.reloq.tts;
import java.util.regex.Pattern;
final class CamelCase {
 private static final Pattern LOWER_TO_UPPER=Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
 private static final Pattern ACRONYM_TO_WORD=Pattern.compile("(?<=[A-Z])(?=[A-Z][a-z])");
 static String apply(String text) {
  if(text==null||text.isEmpty())return text;
  return LOWER_TO_UPPER.matcher(ACRONYM_TO_WORD.matcher(text).replaceAll(" ")).replaceAll(" ");
 }
 private CamelCase(){}
}
