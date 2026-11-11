package org.reloq.tts;
final class EmojiProcessing {
 static final int MIN_COMPRESS=2,MAX_COMPRESS=5,DEFAULT_COMPRESS=3;
 static String apply(String text,int threshold) {
  if(text.isEmpty()||!EmojiNames.mayContain(text)) return text;
  StringBuilder out=new StringBuilder(text.length());
  for(int i=0;i<text.length();) {
   EmojiNames.Match hit=EmojiNames.matchAt(text,i);
   if(hit==null) { int modifier=EmojiNames.modifierLengthAt(text,i); if(modifier>0){i+=modifier;continue;} out.append(text.charAt(i++));continue; }
   String name=hit.name; int count=1,j=hit.end;
   while(true){EmojiNames.Match next=EmojiNames.matchAt(text,j);if(next==null||!next.name.equals(name))break;count++;j=next.end;}
   int compress=Math.max(MIN_COMPRESS,Math.min(MAX_COMPRESS,threshold));
   if(count>=compress){pad(out);out.append(count).append(' ').append(name);if(j>=text.length()||!Character.isWhitespace(text.charAt(j)))out.append(' ');i=j;continue;}
   int k=i;
   for(int n=0;n<count;n++){EmojiNames.Match one=EmojiNames.matchAt(text,k);pad(out);out.append(one.name);k=one.end;if(k>=text.length()||!Character.isWhitespace(text.charAt(k)))out.append(' ');}
   i=j;
  }
  return out.toString().trim();
 }
 private static void pad(StringBuilder out){if(out.length()>0&&!Character.isWhitespace(out.charAt(out.length()-1)))out.append(' ');}
 private EmojiProcessing(){}
}
