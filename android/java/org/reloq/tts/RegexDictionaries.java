package org.reloq.tts;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
final class RegexDictionaries {
 static final class Entry {
  final String pattern,replacement; final Pattern compiled;
  Entry(String p,String r){pattern=p;replacement=r;compiled=Pattern.compile(p);}
 }
 static final class Loaded {
  final List<Entry> entries; final String path; final long modified;
  Loaded(List<Entry> e,File f){entries=e;path=f.getAbsolutePath();modified=f.lastModified();}
 }
 static final class RawEntry {
  final String pattern,replacement;
  RawEntry(String p,String r){pattern=p;replacement=r;}
 }
 static Loaded load(File file) {
  if(file==null||!file.isFile())return null;
  ArrayList<Entry> entries=new ArrayList<>();
  try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
   String line;while((line=in.readLine())!=null){Entry e=entryOf(line);if(e!=null)entries.add(e);}
  }catch(Exception e){System.err.println("ReLoq: "+"Cannot read regex dictionary"); e.printStackTrace();return null;}
  return entries.isEmpty()?null:new Loaded(entries,file);
 }
 static String apply(Loaded loaded,String text) {
  if(loaded==null||text==null||text.isEmpty())return text;
  String out=text;for(Entry e:loaded.entries)try{out=e.compiled.matcher(out).replaceAll(e.replacement);}catch(RuntimeException bad){System.err.println("ReLoq: "+"Regex replacement failed: "+e.pattern,bad);}
  return out;
 }
 static boolean current(Loaded l){if(l==null)return false;File f=new File(l.path);return f.isFile()&&f.lastModified()==l.modified;}
 static int count(File f){return readEntries(f).size();}
 static List<RawEntry> readEntries(File f){
  ArrayList<RawEntry> out=new ArrayList<>();if(f==null||!f.isFile())return out;
  try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
   String line;while((line=in.readLine())!=null){String[] p=split(line);if(p!=null)try{Pattern.compile(p[0]);out.add(new RawEntry(p[0],p[1]));}catch(PatternSyntaxException ignored){}}
  }catch(Exception e){System.err.println("ReLoq: "+"Cannot list regex dictionary"); e.printStackTrace();}return out;
 }
 static boolean append(File f,String pattern,String replacement,boolean matchCase){
  String p=pattern==null?"":pattern.trim(),r=replacement==null?"":replacement.trim();if(p.isEmpty()||r.isEmpty())return false;
  if(!matchCase&&!p.startsWith("(?"))p="(?i)"+p;
  try{Pattern.compile(p);File parent=f.getParentFile();if(parent!=null)parent.mkdirs();
   try(FileOutputStream out=new FileOutputStream(f,true)){if(f.length()>0){RandomAccessFile check=new RandomAccessFile(f,"r");check.seek(f.length()-1);int last=check.read();check.close();if(last!='\n')out.write('\n');}
    out.write((p+"\t"+r+"\n").getBytes(StandardCharsets.UTF_8));}return true;
  }catch(Exception e){System.err.println("ReLoq: "+"Cannot append regex entry"); e.printStackTrace();return false;}
 }
 static boolean replace(File f,int index,String pattern,String replacement,boolean matchCase){return rewrite(f,index,prepare(pattern,replacement,matchCase));}
 static boolean remove(File f,int index){return rewrite(f,index,null);}
 private static String prepare(String p,String r,boolean matchCase){
  p=p==null?"":p.trim();r=r==null?"":r.trim();if(p.isEmpty()||r.isEmpty())return null;
  if(!matchCase&&!p.startsWith("(?"))p="(?i)"+p;
  try{Pattern.compile(p);return p+"\t"+r;}catch(Exception e){return null;}
 }
 private static boolean rewrite(File f,int index,String replacement){
  if(f==null||index<0)return false;
  try{List<String> lines=java.nio.file.Files.readAllLines(f.toPath(),StandardCharsets.UTF_8);ArrayList<String> out=new ArrayList<>();int seen=-1;boolean changed=false;
   for(String line:lines){String[] p=split(line);boolean usable=false;if(p!=null)try{Pattern.compile(p[0]);usable=true;}catch(Exception ignored){}
    if(usable)seen++;if(usable&&seen==index){changed=true;if(replacement!=null)out.add(replacement);}else out.add(line);}
   if(!changed)return false;try(Writer w=new OutputStreamWriter(new FileOutputStream(f,false),StandardCharsets.UTF_8)){for(String line:out){w.write(line);w.write('\n');}}return true;
  }catch(Exception e){System.err.println("ReLoq: "+"Cannot rewrite regex dictionary"); e.printStackTrace();return false;}
 }
 private static Entry entryOf(String line){String[] p=split(line);if(p==null)return null;try{return new Entry(p[0],p[1]);}catch(Exception e){System.err.println("ReLoq: "+"Skipping invalid regex: "+p[0]); e.printStackTrace();return null;}}
 private static String[] split(String line){if(line==null||line.trim().isEmpty()||line.trim().startsWith("#"))return null;int at=line.indexOf('\t');if(at<=0)return null;String p=line.substring(0,at).trim(),r=line.substring(at+1).trim();return p.isEmpty()||r.isEmpty()?null:new String[]{p,r};}
 private RegexDictionaries(){}
}
