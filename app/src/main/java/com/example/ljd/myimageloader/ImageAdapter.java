package com.example.ljd.myimageloader;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ljd-pc on 2016/9/4.
 */
class ImageAdapter extends BaseAdapter {

    private static final String TAG = "ImageAdapter";
    private List<String> mUrList;
    private boolean mIsGridViewIdle;
    private int mImageWidth;
    private ImageLoader mImageLoader;
    private LayoutInflater mInflater;
    private Drawable mDefaultBitmapDrawable;



    private boolean mCanGetBitmapFromNetWork;

    @Override
    public int getCount() {
        return mUrList.size();
    }

    @Override
    public String getItem(int position) {
        return mUrList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void setCanGetBitmapFromNetWork(boolean mCanGetBitmapFromNetWork) {
        this.mCanGetBitmapFromNetWork = mCanGetBitmapFromNetWork;
    }

    public void setIsGridViewIdle(boolean mIsGridViewIdle) {
        this.mIsGridViewIdle = mIsGridViewIdle;
    }



    public ImageAdapter(Context context,List<String> list,
                        int mImageWidth,ImageLoader mImageLoader,
                        boolean mCanGetBitmapFromNetWork,
                        boolean mIsGridViewIdle){
        mInflater = LayoutInflater.from(context);
        mDefaultBitmapDrawable = context.getResources().getDrawable(R.mipmap.image_default);
        this.mUrList = list;
        this.mImageLoader = mImageLoader;
        this.mImageWidth = mImageWidth;
        this.mCanGetBitmapFromNetWork = mCanGetBitmapFromNetWork;
        this.mIsGridViewIdle = mIsGridViewIdle;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Log.v(TAG,"getView convertView = "+convertView);
        ViewHolder holder = null;
        if(convertView == null){
            convertView = mInflater.inflate(R.layout.gird_item,parent, false);
            holder = new ViewHolder();
            holder.imageView = (ImageView) convertView.findViewById(R.id.image);
            convertView.setTag(holder);
        }else{
            holder = (ViewHolder) convertView.getTag();
        }
        ImageView imageView = holder.imageView;
        final String tag = (String)imageView.getTag();
        final String uri = getItem(position);
        if (!uri.equals(tag)) {
            Log.v(TAG,"!uri.equals(tag)====>使用默认图片");
            imageView.setImageDrawable(mDefaultBitmapDrawable);
        }
        if (mIsGridViewIdle && mCanGetBitmapFromNetWork) {
            Log.v(TAG,"mIsGridViewIdle && mCanGetBitmapFromNetWork===>从网络下载");
            imageView.setTag(uri);
            mImageLoader.bindBitmap(uri, imageView, mImageWidth, mImageWidth);
        }
        return convertView;
    }

    class ViewHolder{
        public ImageView imageView;
        public ViewHolder(){

        }
    }
}
