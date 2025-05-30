package org.opencv.android;

import android.content.Context;

/**
 * Helper class provides common initialization methods for OpenCV library.
 */
public class OpenCVLoader
{
    /**
     * Current OpenCV Library version
     */
    public static final String OPENCV_VERSION = "4.11.0";


    /**
     * Synonym for initLocal. Deprecated.
     */
    @Deprecated
    public static boolean initDebug()
    {
        return StaticHelper.initOpenCV(false);
    }

    /**
     * Loads and initializes OpenCV library from current application package. Roughly, it's an analog of system.loadLibrary("opencv_java").
     * @return Returns true is initialization of OpenCV was successful.
     */
    public static boolean initLocal()
    {
        return StaticHelper.initOpenCV(false);
    }

    /**
     * Loads and initializes OpenCV library from current application package. Roughly, it's an analog of system.loadLibrary("opencv_java").
     * @param InitCuda load and initialize CUDA runtime libraries.
     * @return Returns true is initialization of OpenCV was successful.
     */
    @Deprecated
    public static boolean initDebug(boolean InitCuda)
    {
        return StaticHelper.initOpenCV(InitCuda);
    }
}
