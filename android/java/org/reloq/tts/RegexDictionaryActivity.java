package org.reloq.tts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.util.List;

/** Editor and settings for the user regular-expression dictionary. */
public final class RegexDictionaryActivity extends Activity {
    private static final int PICK=41, SAVE=42;
    private SharedPreferences prefs;
    private LinearLayout content;
    private Switch enabled;
    private TextView fileName;
    private File file;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("settings",MODE_PRIVATE);
        String path=prefs.getString("regex_path",null);
        File selected=path==null?null:new File(path);
        file=selected!=null&&selected.isFile()?selected:new File(getFilesDir(),"dictionaries/regex.dic");
        if(path!=null&&(selected==null||!selected.isFile()))prefs.edit().remove("regex_path").remove("regex_name").putBoolean("regex_enabled",false).apply();
        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density);
        content.setPadding(pad,pad,pad,pad);
        scroll.addView(content);
        setContentView(scroll);

        TextView title=new TextView(this);
        title.setText("Regular-expression dictionary settings");
        title.setTextSize(22);
        content.addView(title);

        enabled=new Switch(this);
        enabled.setText("Enable regular-expression dictionary");
        enabled.setChecked(prefs.getBoolean("regex_enabled",false)&&file.isFile());
        content.addView(enabled);
        enabled.setOnCheckedChangeListener((v,on)->
            prefs.edit().putBoolean("regex_enabled",on&&file.isFile()).apply());

        fileName=new TextView(this);
        content.addView(fileName);
        Button importButton=button("Import dictionary file");
        importButton.setOnClickListener(v->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i,PICK);
        });
        Button add=button("Add regular-expression entry");
        add.setOnClickListener(v->editEntry(-1,null));
        Button export=button("Export dictionary");
        export.setOnClickListener(v->{
            if(!file.isFile())return;
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE,"regex.dic");
            startActivityForResult(i,SAVE);
        });
        refreshEntries();
    }

    private Button button(String label) {
        Button b=new Button(this);
        b.setText(label);
        content.addView(b);
        return b;
    }

    private void refreshEntries() {
        fileName.setText(file.isFile()?file.getName()+" ("+RegexDictionaries.count(file)+" entries)":"No dictionary selected");
        while(content.getChildCount()>6)content.removeViewAt(6);
        if(!file.isFile())return;
        List<RegexDictionaries.RawEntry> entries=RegexDictionaries.readEntries(file);
        TextView heading=new TextView(this);
        heading.setText("Entries: "+entries.size());
        heading.setTextSize(18);
        content.addView(heading);
        for(int i=0;i<entries.size();i++) {
            final int at=i;
            RegexDictionaries.RawEntry entry=entries.get(i);
            TextView row=new TextView(this);
            row.setText(entry.pattern+" → "+entry.replacement);
            row.setTextSize(16);
            row.setPadding(0,14,0,14);
            row.setContentDescription("Regular expression "+entry.pattern+", replacement "+entry.replacement+". Double tap to edit or delete.");
            row.setFocusable(true);
            row.setOnClickListener(v->entryActions(at,entry));
            content.addView(row);
        }
    }

    private void entryActions(int at,RegexDictionaries.RawEntry entry) {
        new AlertDialog.Builder(this).setItems(new String[]{"Edit","Delete","Cancel"},(d,which)->{
            if(which==0)editEntry(at,entry);
            else if(which==1)new AlertDialog.Builder(this)
                .setMessage("Delete this regular-expression entry?")
                .setPositiveButton("Delete",(x,y)->{
                    if(RegexDictionaries.remove(file,at)) {
                        if(RegexDictionaries.count(file)==0) {
                            file.delete();
                            prefs.edit().remove("regex_path").remove("regex_name").putBoolean("regex_enabled",false).apply();
                            enabled.setChecked(false);
                        }
                        refreshEntries();
                    }
                }).setNegativeButton("Cancel",null).show();
        }).show();
    }

    private void editEntry(int at,RegexDictionaries.RawEntry old) {
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(12*getResources().getDisplayMetrics().density);
        box.setPadding(pad,pad,pad,pad);
        EditText pattern=new EditText(this);
        pattern.setSingleLine(true);
        pattern.setHint("Regular expression pattern");
        pattern.setText(old==null?"":(old.pattern.startsWith("(?i)")?old.pattern.substring(4):old.pattern));
        box.addView(pattern);
        EditText replacement=new EditText(this);
        replacement.setSingleLine(true);
        replacement.setHint("Replacement text");
        replacement.setText(old==null?"":old.replacement);
        box.addView(replacement);
        CheckBox matchCase=new CheckBox(this);
        matchCase.setText("Match case");
        matchCase.setChecked(old!=null&&!old.pattern.startsWith("(?i)"));
        box.addView(matchCase);
        new AlertDialog.Builder(this).setTitle(old==null?"Add entry":"Edit entry").setView(box)
            .setPositiveButton("Save",(d,w)->{
                boolean ok=at<0
                    ?RegexDictionaries.append(file,pattern.getText().toString(),replacement.getText().toString(),matchCase.isChecked())
                    :RegexDictionaries.replace(file,at,pattern.getText().toString(),replacement.getText().toString(),matchCase.isChecked());
                if(ok) {
                    prefs.edit().putString("regex_path",file.getAbsolutePath()).putString("regex_name",file.getName()).putBoolean("regex_enabled",true).apply();
                    enabled.setChecked(true);
                    refreshEntries();
                } else Toast.makeText(this,"Invalid pattern, empty replacement, or file error",Toast.LENGTH_LONG).show();
            }).setNegativeButton("Cancel",null).show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(requestCode==PICK) {
            try(InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IOException("Cannot open selected file");
                File target=new File(getFilesDir(),"dictionaries/regex.dic");
                File parent=target.getParentFile();
                if(parent!=null)parent.mkdirs();
                File pending=new File(parent,"regex-import.tmp");
                try(OutputStream out=new FileOutputStream(pending)) {
                    byte[] buf=new byte[8192];int n;
                    while((n=in.read(buf))!=-1)out.write(buf,0,n);
                }
                if(RegexDictionaries.count(pending)==0) {
                    pending.delete();
                    Toast.makeText(this,"No valid pattern/replacement entries found",Toast.LENGTH_LONG).show();
                    return;
                }
                if(target.exists())target.delete();
                if(!pending.renameTo(target))throw new IOException("Cannot install imported dictionary");
                file=target;
                prefs.edit().putString("regex_path",file.getAbsolutePath()).putString("regex_name",file.getName()).putBoolean("regex_enabled",true).apply();
                enabled.setChecked(true);
                refreshEntries();
            } catch(Exception e) {
                Toast.makeText(this,"Import failed: "+e.getMessage(),Toast.LENGTH_LONG).show();
            }
        } else if(requestCode==SAVE) {
            try(OutputStream out=getContentResolver().openOutputStream(uri);InputStream in=new FileInputStream(file)) {
                if(out==null)throw new IOException("Cannot open destination");
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
            } catch(Exception e) {
                Toast.makeText(this,"Export failed: "+e.getMessage(),Toast.LENGTH_LONG).show();
            }
        }
    }
}
