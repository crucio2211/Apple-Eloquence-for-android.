package org.reloq.tts;
final class PunctuationProcessing {
 static final int NONE=0,SOME=1,MOST=2,ALL=3;
 static String apply(String text,int level) {
  if(text.isEmpty())return text;
  StringBuilder out=new StringBuilder(text.length()*2);
  for(int i=0;i<text.length();) {
   int cp=text.codePointAt(i),adv=Character.charCount(cp); String rep=null; int pres=PunctSymbols.PASS;
   boolean ending=isSentenceMark(cp)&&isSentenceEnding(text,i);
   boolean standaloneDot=cp==46&&(i==0||Character.isWhitespace(text.charAt(i-1)))&&(i+adv>=text.length()||Character.isWhitespace(text.charAt(i+adv)));
   if(cp>0x20){PunctSymbols.Entry e=PunctSymbols.lookup(cp);if(e!=null){pres=e.pres;if(e.level<=level)rep=e.word;}}
   if((cp==46||cp==44)&&i>0&&i+adv<text.length()&&digit(text.charAt(i-1))&&digit(text.charAt(i+adv))){rep=null;pres=PunctSymbols.PASS;}
   if(cp==46&&level>=SOME&&rep!=null&&!ending&&!standaloneDot&&!(i>0&&i+adv<text.length()&&digit(text.charAt(i-1))&&digit(text.charAt(i+adv)))){rep="dot";pres=PunctSymbols.PASS;}
   if(standaloneDot&&(level==SOME||level==MOST)){rep=null;pres=PunctSymbols.NEVER;}
   if(ending){if(cp==46||level<ALL)rep=null;pres=PunctSymbols.ALWAYS;}
   if(rep!=null){pad(out);out.append(rep);if(pres==PunctSymbols.ALWAYS||pres==PunctSymbols.NOREP)out.append(' ').append((char)96).append("p1");if(i+adv>=text.length()||!Character.isWhitespace(text.charAt(i+adv)))out.append(' ');}
   else if(pres!=PunctSymbols.NEVER)out.append(text,i,i+adv);
   i+=adv;
  }
  return out.toString().trim();
 }
 private static void pad(StringBuilder out){if(out.length()>0&&!Character.isWhitespace(out.charAt(out.length()-1)))out.append(' ');}
 private static boolean isSentenceMark(int cp){return cp==46||cp==33||cp==63||cp==0xFF01||cp==0xFF1F||cp==0x061F;}
 private static boolean digit(char c){return c>='0'&&c<='9';}
 private static boolean isSentenceEnding(String text,int at){
  if(at==0)return false;char prev=text.charAt(at-1);if(prev==' '||prev==9)return false;
  int j=at+1;while(j<text.length()){char c=text.charAt(j);if(c!=32&&c!=9&&c!=39&&c!=34&&c!=0x201D&&c!=0x2019&&c!=41&&c!=93)break;j++;}
  if(j>=text.length())return true;char c=text.charAt(j);if(c>='A'&&c<='Z')return true;if(c>='a'&&c<='z')return false;if(digit(c))return false;return true;
 }
 private PunctuationProcessing(){}
}
