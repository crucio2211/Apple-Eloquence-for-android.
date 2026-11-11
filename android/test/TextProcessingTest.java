package org.reloq.tts;

public final class TextProcessingTest {
    private static void eq(String expected,String actual,String label) {
        if(!expected.equals(actual)) throw new AssertionError(label+": expected <"+expected+">, got <"+actual+">");
    }
    private static String emoji(int... points) {
        StringBuilder out=new StringBuilder();
        for(int point:points) out.append(new String(Character.toChars(point)));
        return out.toString();
    }
    public static void main(String[] args) {
        eq("1000",NumberProcessing.stripThousandsCommas("1,000"),"thousands comma");
        eq("1 23 45",NumberProcessing.apply("12345",NumberProcessing.PAIRS),"pairs");
        eq("1 234 567",NumberProcessing.apply("1234567",NumberProcessing.TRIPLETS),"triplets");
        eq("3000",NumberProcessing.apply("3000",NumberProcessing.QUADRUPLETS),"quadruplets");
        eq("3 grinning face",EmojiProcessing.apply("😀😀😀",3),"emoji compression");
        eq("cracking face",EmojiProcessing.apply(emoji(0x1FAEB),3),"Emoji 18 cracking face");
        eq("leftwards thumb sign",EmojiProcessing.apply(emoji(0x1FAF9),3),"Emoji 18 left thumb");
        eq("rightwards thumb sign: dark skin tone",EmojiProcessing.apply(emoji(0x1FAFA,0x1F3FF),3),"Emoji 18 right thumb tone");
        eq("monarch butterfly",EmojiProcessing.apply(emoji(0x1FACC),3),"Emoji 18 monarch");
        eq("pickle",EmojiProcessing.apply(emoji(0x1FADD),3),"Emoji 18 pickle");
        eq("lighthouse",EmojiProcessing.apply(emoji(0x1F6D9),3),"Emoji 18 lighthouse");
        eq("meteor",EmojiProcessing.apply(emoji(0x1FA8B),3),"Emoji 18 meteor");
        eq("eraser",EmojiProcessing.apply(emoji(0x1FA8C),3),"Emoji 18 eraser");
        eq("net with handle",EmojiProcessing.apply(emoji(0x1FA8D),3),"Emoji 18 net");
        eq("Hello comma "+(char)96+"p1 world",PunctuationProcessing.apply("Hello, world",PunctuationProcessing.ALL),"comma punctuation");
        eq("Hello!",PunctuationProcessing.apply("Hello!",PunctuationProcessing.MOST),"sentence prosody");
        eq("1000",TextPipeline.process("1,000",false,3,false,0,false,2),"pipeline comma pass");
        System.out.println("Android text processing checks passed (Emoji 18.0, compression, numbers, punctuation).");
    }
}
