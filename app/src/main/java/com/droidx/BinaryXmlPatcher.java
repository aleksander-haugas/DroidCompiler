package com.droidx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Minimal Android binary-XML editor used for exported APK metadata. */
final class BinaryXmlPatcher {
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int UTF8_FLAG = 0x00000100;

    private BinaryXmlPatcher() {}

    static byte[] patchManifest(byte[] input, String packageName, String appName, String versionName, int versionCode) throws Exception {
        byte[] data = input.clone();
        if (u16(data,0) != RES_XML_TYPE) throw new IllegalArgumentException("Not Android binary XML");
        int pos = u16(data,2);
        StringPool pool = null;
        int poolPos = -1;
        while (pos + 8 <= data.length) {
            int type=u16(data,pos), size=u32(data,pos+4);
            if(size<8 || pos+size>data.length) throw new IllegalArgumentException("Invalid binary XML chunk");
            if(type==RES_STRING_POOL_TYPE){ pool=parsePool(data,pos); poolPos=pos; break; }
            pos+=size;
        }
        if(pool==null) throw new IllegalArgumentException("Manifest string pool not found");
        replace(pool.strings, "com.droidx.u00000000", packageName);
        replace(pool.strings, "DROIDX_APP______________________________________________________", appName);
        replace(pool.strings, "DROIDX_VERSION___", versionName);
        byte[] newPool = buildPool(pool);
        int oldSize = pool.chunkSize;
        byte[] out = new byte[data.length - oldSize + newPool.length];
        System.arraycopy(data,0,out,0,poolPos);
        System.arraycopy(newPool,0,out,poolPos,newPool.length);
        System.arraycopy(data,poolPos+oldSize,out,poolPos+newPool.length,data.length-(poolPos+oldSize));
        put32(out,4,out.length);
        patchIntAttribute(out, "manifest", "versionCode", versionCode);
        return out;
    }

    private static void patchIntAttribute(byte[] data,String elementName,String attrName,int value)throws Exception{
        StringPool pool=null; int pos=u16(data,2);
        while(pos+8<=data.length){int type=u16(data,pos),size=u32(data,pos+4);if(type==RES_STRING_POOL_TYPE)pool=parsePool(data,pos);if(size<8||pos+size>data.length)break;
            if(type==RES_XML_START_ELEMENT_TYPE && pool!=null){
                int elemIdx=u32(data,pos+20);
                if(elemIdx>=0&&elemIdx<pool.strings.size()&&elementName.equals(pool.strings.get(elemIdx))){
                    int attrStart=u16(data,pos+24), attrSize=u16(data,pos+26), attrCount=u16(data,pos+28);
                    int a0=pos+16+attrStart;
                    for(int i=0;i<attrCount;i++){
                        int a=a0+i*attrSize; if(a+20>pos+size)break;
                        int nameIdx=u32(data,a+4);
                        if(nameIdx>=0&&nameIdx<pool.strings.size()&&attrName.equals(pool.strings.get(nameIdx))){
                            data[a+15]=0x10; // TYPE_INT_DEC
                            put32(data,a+16,value); return;
                        }
                    }
                }
            }
            pos+=size;
        }
        throw new IllegalStateException("Manifest attribute not found: "+elementName+"/@"+attrName);
    }

    private static void replace(List<String> strings,String from,String to){
        boolean found=false; for(int i=0;i<strings.size();i++)if(from.equals(strings.get(i))){strings.set(i,to);found=true;}
        if(!found) throw new IllegalStateException("Manifest placeholder missing: "+from);
    }

    private static final class StringPool{
        int headerSize,chunkSize,stringCount,styleCount,flags,stringsStart,stylesStart;
        List<String> strings=new ArrayList<>(); byte[] styles;
    }

    private static StringPool parsePool(byte[] d,int off)throws Exception{
        StringPool p=new StringPool(); p.headerSize=u16(d,off+2);p.chunkSize=u32(d,off+4);p.stringCount=u32(d,off+8);p.styleCount=u32(d,off+12);p.flags=u32(d,off+16);p.stringsStart=u32(d,off+20);p.stylesStart=u32(d,off+24);
        boolean utf8=(p.flags&UTF8_FLAG)!=0; int offs=off+p.headerSize;
        for(int i=0;i<p.stringCount;i++){int so=u32(d,offs+i*4);p.strings.add(readString(d,off+p.stringsStart+so,utf8));}
        if(p.stylesStart!=0){int styleAbs=off+p.stylesStart; p.styles=Arrays.copyOfRange(d,styleAbs,off+p.chunkSize);} else p.styles=new byte[0];
        return p;
    }

    private static byte[] buildPool(StringPool p)throws Exception{
        boolean utf8=(p.flags&UTF8_FLAG)!=0; ByteArrayOutputStream strings=new ByteArrayOutputStream(); int[] offsets=new int[p.strings.size()];
        for(int i=0;i<p.strings.size();i++){offsets[i]=strings.size();writeString(strings,p.strings.get(i),utf8);}
        while((strings.size()&3)!=0)strings.write(0);
        int headerSize=28; int stringsStart=headerSize+p.stringCount*4+p.styleCount*4; int stylesStart=p.styles.length>0?stringsStart+strings.size():0; int chunkSize=(stylesStart>0?stylesStart+p.styles.length:stringsStart+strings.size());
        byte[] out=new byte[chunkSize];put16(out,0,RES_STRING_POOL_TYPE);put16(out,2,headerSize);put32(out,4,chunkSize);put32(out,8,p.stringCount);put32(out,12,p.styleCount);put32(out,16,p.flags);put32(out,20,stringsStart);put32(out,24,stylesStart);
        int x=headerSize;for(int v:offsets){put32(out,x,v);x+=4;}for(int i=0;i<p.styleCount;i++){put32(out,x,0xffffffff);x+=4;}
        byte[] sb=strings.toByteArray();System.arraycopy(sb,0,out,stringsStart,sb.length);if(p.styles.length>0)System.arraycopy(p.styles,0,out,stylesStart,p.styles.length);return out;
    }

    private static String readString(byte[] d,int pos,boolean utf8)throws Exception{
        if(utf8){int[] a=readLen8(d,pos);pos=a[1];int[] b=readLen8(d,pos);int bytes=b[0];pos=b[1];return new String(d,pos,bytes,StandardCharsets.UTF_8);}else{int[] a=readLen16(d,pos);int chars=a[0];pos=a[1];return new String(d,pos,chars*2,StandardCharsets.UTF_16LE);}
    }
    private static void writeString(ByteArrayOutputStream out,String s,boolean utf8)throws Exception{
        if(utf8){byte[] b=s.getBytes(StandardCharsets.UTF_8);writeLen8(out,s.length());writeLen8(out,b.length);out.write(b);out.write(0);}else{byte[] b=s.getBytes(StandardCharsets.UTF_16LE);writeLen16(out,s.length());out.write(b);out.write(0);out.write(0);}
    }
    private static int[] readLen8(byte[] d,int p){int a=d[p++]&255;if((a&128)!=0){int b=d[p++]&255;return new int[]{((a&127)<<8)|b,p};}return new int[]{a,p};}
    private static int[] readLen16(byte[] d,int p){int a=u16(d,p);p+=2;if((a&0x8000)!=0){int b=u16(d,p);p+=2;return new int[]{((a&0x7fff)<<16)|b,p};}return new int[]{a,p};}
    private static void writeLen8(ByteArrayOutputStream o,int n){if(n>0x7f){o.write(((n>>8)&0x7f)|0x80);o.write(n&255);}else o.write(n);}
    private static void writeLen16(ByteArrayOutputStream o,int n){if(n>0x7fff){int a=((n>>16)&0x7fff)|0x8000;o.write(a&255);o.write((a>>8)&255);o.write(n&255);o.write((n>>8)&255);}else{o.write(n&255);o.write((n>>8)&255);}}
    private static int u16(byte[]d,int o){return(d[o]&255)|((d[o+1]&255)<<8);} private static int u32(byte[]d,int o){return(d[o]&255)|((d[o+1]&255)<<8)|((d[o+2]&255)<<16)|((d[o+3]&255)<<24);}
    private static void put16(byte[]d,int o,int v){d[o]=(byte)v;d[o+1]=(byte)(v>>>8);} private static void put32(byte[]d,int o,int v){d[o]=(byte)v;d[o+1]=(byte)(v>>>8);d[o+2]=(byte)(v>>>16);d[o+3]=(byte)(v>>>24);}
}
