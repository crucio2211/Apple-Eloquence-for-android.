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
        eq("user at example.com",SymbolWords.apply("user@example.com"),"at sign expands to at");
        eq("at home",SymbolWords.apply("@home"),"leading at sign");
        eq("mail me at a at b",SymbolWords.apply("mail me@a@b"),"multiple at signs");
        eq("example.com",SymbolWords.apply("example.com"),"other symbols untouched");
        eq("a+b",SymbolWords.apply("a+b"),"plus untouched");
        eq("Hello comma "+(char)96+"p1 world",PunctuationProcessing.apply("Hello, world",PunctuationProcessing.ALL),"comma punctuation");
        eq("Hello!",PunctuationProcessing.apply("Hello!",PunctuationProcessing.MOST),"sentence prosody");
        eq("",PunctuationProcessing.apply(". .",PunctuationProcessing.SOME),"isolated dots in Some");
        eq("",PunctuationProcessing.apply(". .",PunctuationProcessing.MOST),"isolated dots in Most");
        eq("dot dot",PunctuationProcessing.apply(". .",PunctuationProcessing.ALL),"All still speaks isolated dots");
        eq("dot.",PunctuationProcessing.apply("dot.",PunctuationProcessing.SOME),"sentence-ending dot remains punctuation");
        eq("Chat Gpt Talk Back PDF File i Phone NVDA",CamelCase.apply("ChatGpt TalkBack PDFFile iPhone NVDA"),"camel-case splitting");
        try {
            java.io.File regexFile=java.io.File.createTempFile("reloq-regex",".dic");
            if(!RegexDictionaries.append(regexFile,"\\bNVDA\\b","en vee dee ay",false))throw new AssertionError("regex append");
            RegexDictionaries.Loaded loaded=RegexDictionaries.load(regexFile);
            eq("en vee dee ay and en vee dee ay",RegexDictionaries.apply(loaded,"NVDA and nvda"),"regex dictionary case-insensitive replacement");
            regexFile.delete();
        } catch(java.io.IOException error) { throw new AssertionError(error); }
        eq("1000",TextPipeline.process("1,000",false,3,false,0,false,false,2),"pipeline comma pass");
        eq("user at example.com",TextPipeline.process("user@example.com",false,3,false,0,false,false,2),"at sign when punctuation is off");
        eq("user at example dot com",TextPipeline.process("user@example.com",false,3,false,0,false,true,2),"at sign stays at while punctuation handles the domain dot");
        System.out.println("Android text processing checks passed (Emoji 18.0, compression, numbers, camel case, regex, symbol words, punctuation).");
    }
}
