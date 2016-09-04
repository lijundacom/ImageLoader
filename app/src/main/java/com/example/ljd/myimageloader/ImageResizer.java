package com.example.ljd.myimageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileDescriptor;

/**
 * Created by ljd-pc on 2016/9/4.
 */
public class ImageResizer {
    private static final String TAG = "ImageResizer";
    public ImageResizer(){

    }
    //以采样率的方式加载图片
    public Bitmap decodeSampledBitmapFromResource(Resources res,
                                                  int resId, int reqWidth, int reqHeight){
        //first decode with inJustDecodeBounds = true to check demensions
        final BitmapFactory.Options options= new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res,resId,options);

        //calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options,reqWidth,reqHeight);

        //decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res,resId,options);
    }

    //从文件描述符加载图片
    public Bitmap decodeSampleBitmapFromFileDescriptor(FileDescriptor fd,
                                                       int reqWidth, int reqHeight){
        //first decode with inJustDecodeBounds = true to check demensions
        final BitmapFactory.Options options= new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd,null,options);

        //calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options,reqWidth,reqHeight);

        //decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd,null,options);
    }

    public int calculateInSampleSize(BitmapFactory.Options options,
                                     int reqWidth, int reqHeight){
        if(reqWidth == 0 || reqHeight == 0){
            return 1;
        }

        //raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if(height>reqHeight || width>reqWidth){
            final int halfHeight = height/2;
            final int halfWidth = width/2;

            while((halfHeight/inSampleSize)>=reqHeight
                    &&(halfWidth/inSampleSize)>=reqWidth){
                inSampleSize*=2;
            }

        }
        return inSampleSize;
    }


}
