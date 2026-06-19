package com.myapp.haircolor;

/**
 * Result object filled by the native TNN engine (libdev_hair.so).
 *
 * The package, class name and field names MUST stay exactly as in the original
 * app — the native code looks them up by name via JNI GetFieldID.
 */
public class ImageInfo {
    public byte[] data;
    public int image_channel;
    public int image_height;
    public int image_width;
}
