package com.myapp.haircolor;

/**
 * JNI bridge to the original native hair-segmentation engine (TNN), packaged as
 * libdev_hair.so. The package + class + method names MUST match the original app
 * exactly, because the native exported symbols are
 * Java_com_myapp_haircolor_DevHairSegmentation_*.
 *
 * - init(modelDir, key, mode): modelDir = folder containing segmentation.devmodel
 *   and segmentation.devproto; key = decrypted license; mode = 1 (photo) / 0 (camera).
 *   Returns 0 on success.
 * - setHairColor(rgba): 4-byte {R,G,B,A} target colour.
 * - predictFromStream(nv21, tag, height, width, rotation): returns ImageInfo[2];
 *   index [1] is the RGBA result whose alpha channel is the hair mask.
 */
public class DevHairSegmentation {

    static {
        System.loadLibrary("dev_hair");
    }

    public native boolean checkNpu(String str);

    public native int deinit();

    public native int init(String modelDir, String key, int mode);

    public native ImageInfo[] predictFromStream(byte[] data, String tag, int height, int width, int rotation);

    public native int setHairColor(byte[] rgba);
}
