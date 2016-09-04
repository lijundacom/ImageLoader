package com.example.ljd.myimageloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.util.StringBuilderPrinter;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

import libcore.io.DiskLruCache;

/**
 * Created by ljd-pc on 2016/9/4.
 */
public class ImageLoader {
    private static final String TAG = "ImageLoader";

    public static final int MESSAGE_P0ST_RESULT = 1;
    //计算CPU核数
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    //线程池核心池大小
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    //线程池最大线程数
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT*2+1;
    //线程池空闲线程存活时间
    private static final long KEEP_ALIVE = 10L;

    private static final int TAG_KEY_URI = R.id.imageloader_uri;

    private static final int DISK_CACHE_SIZE = 1024*1024*50;
    private static final int IO_BUFFER_SIZE = 8*1024;

    private static final int DISK_CACHE_INDEX = 0;
    private boolean mIsDiskLruCacheCreated = false;
    //线程工厂
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r,"ImageLoader#"+mCount.getAndIncrement());
        }
    };
    //线程池
    private static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE,MAXIMUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingDeque<Runnable>(),sThreadFactory
    );
    //子线程下载图片，主线程从消息队列中取出图片并更新UI
    private Handler mMainHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult)msg.obj;
            ImageView imageView = result.imageView;
            //imageView.setImageBitmap(result.bitmap);
            String uri = (String) imageView.getTag(TAG_KEY_URI);
            if(uri.equals(result.uri)){
                imageView.setImageBitmap(result.bitmap);
            }else{
                Log.w(TAG,"set image bitmap,but url has changed,ignored!");
            }
        }
    };

    private LruCache<String,Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;
    private Context mContext;
    private ImageResizer mImageResizer = new ImageResizer();

    private ImageLoader(Context context){
        mContext = context.getApplicationContext();
        int maxMemory = (int)(Runtime.getRuntime().maxMemory()/1024);
        int cacheSize = maxMemory/8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getRowBytes()*bitmap.getHeight()/1024;
            }
        };

        File diskCacheDir = gerDiskCacheDir(mContext,"bitmap");
        if(!diskCacheDir.exists()){
            diskCacheDir.mkdir();
        }
        if(getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE){
            try{
                mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            }catch(IOException e){
                e.printStackTrace();
            }
        }
    }

    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    private void addBitmapToMemoryCache(String key,Bitmap bitmap){
        if(getBitmapFormeMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    private Bitmap getBitmapFormeMemCache(String key){
        return mMemoryCache.get(key);
    }

    public void bindBitmap(final String uri,final ImageView imageView){
        bindBitmap(uri,imageView,0,0);
    }

    public void bindBitmap(final String uri,final ImageView imageView,
                           final int reqWidth, final int reqHeight){
        imageView.setTag(TAG_KEY_URI,uri);//将uri存入当前imageView
        //如果内存缓存中有图片，直接在主线程中加载
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if(bitmap != null){
            imageView.setImageBitmap(bitmap);
            return;
        }
        //如果内存缓存中没有图片，异步加载
        final Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri,reqWidth,reqHeight);
                if(bitmap!=null){
                    LoaderResult result = new LoaderResult(imageView,uri,bitmap);
                    mMainHandler.obtainMessage(MESSAGE_P0ST_RESULT,result)
                            .sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }


    public Bitmap loadBitmap(String uri,int reqWidth,int reqHeight){
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if(bitmap!=null){
            Log.d(TAG,"loadbitMapFromMemCache,url:"+uri);
            return bitmap;
        }
        try{
            bitmap = loadBitmapFromDiskCache(uri,reqWidth,reqHeight);
            if(bitmap!=null){
                Log.d(TAG,"loadBitmapFromDisk,url:"+uri);
                return bitmap;
            }
            //如果硬盘缓存创建了，下载先存入赢盘缓存，再从硬盘缓存中获取
            bitmap = loadBitmapFromHttp(uri,reqWidth,reqHeight);
            Log.d(TAG,"loadBitmapFromHttp,url:"+uri);
        }catch (IOException e){
            e.printStackTrace();
        }

        if(bitmap==null && !mIsDiskLruCacheCreated){
            Log.w(TAG,"ERROR:DiskLruCache is not created");
            //如果硬盘缓存没有创建，直接下载，并保存为bitmap
            bitmap = downloadBitmapFromUrl(uri);
        }
        return bitmap;
    }
    //从内存缓存获取
    private Bitmap loadBitmapFromMemCache(String url){
        final String key = hashKeyFromUrl(url);
        Bitmap bitmap = getBitmapFormeMemCache(key);
        return bitmap;
    }
    //每次下载后，图片都会先存储到磁盘缓存中，并从磁盘缓存获取
    private Bitmap loadBitmapFromHttp(String url,int reqWidth,
                                      int reqHeight) throws IOException{
        if(Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit netWork from UI Thread");
        }
        if(mDiskLruCache == null){
            return null;
        }

        String key = hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if(editor != null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if(downloadUrlToStream(url,outputStream)){
                editor.commit();//向缓存中添加
            }else{
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url,reqWidth,reqHeight);
    }
    //每次从磁盘缓存获取一张图片，图片都会存入内存缓存
    private Bitmap loadBitmapFromDiskCache(String url,int reqWidth,int reqHeight)
            throws IOException{
        if(Looper.myLooper() == Looper.getMainLooper()){
            Log.w(TAG,"load bitmap from UI Thread,It's not recommended");
        }
        if(mDiskLruCache == null){
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapShot = mDiskLruCache.get(key);
        if(snapShot!=null){
            FileInputStream fileInputStream = (FileInputStream)snapShot
                    .getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampleBitmapFromFileDescriptor
                    (fileDescriptor,reqWidth,reqHeight);
            if(bitmap!=null){
                addBitmapToMemoryCache(key,bitmap);
            }
        }
        return bitmap;
    }
    //下载的图片转化成outputStream，目的是存入硬盘缓存
    public boolean downloadUrlToStream(String urlString,
                                       OutputStream outputStream){
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try{
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection)url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream,IO_BUFFER_SIZE);
            int b;
            while((b = in.read())!=-1){
                out.write(b);
            }
            return true;
        }catch (IOException e){
            Log.e(TAG,"downloadBitmap failed"+e);
        }finally {
            if(urlConnection!=null){
                urlConnection.disconnect();
            }
            MyUtils.close(out);
            MyUtils.close(in);

        }
        return false;
    }
    //下载的图片直接输出为BitMap
    private Bitmap downloadBitmapFromUrl(String urlString){
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;
        try{
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection)url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        }catch (final IOException e){
            Log.e(TAG,"Error in downloadBitmap"+e);
        }finally {
            if(urlConnection!=null){
                urlConnection.disconnect();
            }
            try {
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return bitmap;
    }

    private String bytesToHexString(byte[] bytes){
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<bytes.length;i++){
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if(hex.length() == 1){
                sb.append(0);
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    public File gerDiskCacheDir(Context context,String uniqueName){
        boolean externalStorageAvailable = Environment
                .getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if(externalStorageAvailable){
            cachePath = context.getExternalCacheDir().getPath();
        }else{
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath+File.separator+uniqueName);
    }

    private long getUsableSpace(File path){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD){
            return path.getUsableSpace();
        }
        final StatFs stats = new StatFs(path.getPath());
        return (long)stats.getBlockSize()*(long)stats.getAvailableBlocks();
    }

    private static class LoaderResult{
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;
        public LoaderResult(ImageView imageView,String uri,Bitmap bitmap){
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }

    private String hashKeyFromUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }
}
