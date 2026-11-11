package org.reloq.tts;
final class TextPipeline {
 static String process(String raw,boolean emoji,int emojiCompress,boolean numbers,int numberMode,boolean punctuation,int punctLevel) {
  if(raw.isEmpty())return raw;
  String text=NumberProcessing.stripThousandsCommas(raw);
  if(emoji)text=EmojiProcessing.apply(text,emojiCompress);
  if(numbers)text=NumberProcessing.apply(text,numberMode);
  if(punctuation)text=PunctuationProcessing.apply(text,punctLevel);
  return text;
 }
 private TextPipeline(){}
}
