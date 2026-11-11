package org.reloq.tts;
final class NumberProcessing {
 static final int DIGITS=0, PAIRS=1, TRIPLETS=2, QUADRUPLETS=3;
 static String stripThousandsCommas(String text) {
  if(text.isEmpty()) return text;
  StringBuilder out=new StringBuilder(text.length());
  for(int i=0;i<text.length();) {
   char c=text.charAt(i);
   if(c==','&&i>0&&i+3<text.length()&&digit(text.charAt(i-1))&&digit(text.charAt(i+1))&&digit(text.charAt(i+2))&&digit(text.charAt(i+3))&&(i+4>=text.length()||!digit(text.charAt(i+4)))) { i++; continue; }
   out.append(c); i++;
  }
  return out.toString();
 }
 static String apply(String text,int mode) {
  if(text.isEmpty()) return text;
  StringBuilder out=new StringBuilder(text.length());
  for(int i=0;i<text.length();) {
   if(!digit(text.charAt(i))) { out.append(text.charAt(i++)); continue; }
   int j=i; while(j<text.length()&&digit(text.charAt(j))) j++;
   String digits=text.substring(i,j); int n=digits.length();
   if(mode==QUADRUPLETS&&n<=4) out.append(digits);
   else if(mode==DIGITS||mode==QUADRUPLETS) spell(out,digits);
   else {
    int size=mode+1;
    if(n<=size) out.append(digits);
    else {
     int rem=n%size,k=0;
     if(rem>0) { out.append(digits.substring(0,rem)); k=rem; }
     while(k<n) { if(k>0) out.append(' '); out.append(digits.substring(k,Math.min(k+size,n))); k+=size; }
    }
   }
   i=j;
  }
  return out.toString();
 }
 private static boolean digit(char c){return c>='0'&&c<='9';}
 private static void spell(StringBuilder out,String digits){for(int i=0;i<digits.length();i++){if(i>0)out.append(' ');out.append(digits.charAt(i));}}
 private NumberProcessing(){}
}
