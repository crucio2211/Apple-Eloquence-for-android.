package org.reloq.tts;
final class TextPipeline {
 static String process(String raw,boolean emoji,int emojiCompress,boolean numbers,int numberMode,boolean camelCase,boolean punctuation,int punctLevel) {
  if(raw.isEmpty())return raw;
  String text=NumberProcessing.stripThousandsCommas(raw);
  if(emoji)text=EmojiProcessing.apply(text,emojiCompress);
  if(numbers)text=NumberProcessing.apply(text,numberMode);
  if(camelCase)text=CamelCase.apply(text);
  text=SymbolWords.apply(text);
  if(punctuation)text=PunctuationProcessing.apply(text,punctLevel);
  return text;
 }
 private TextPipeline(){}
}
