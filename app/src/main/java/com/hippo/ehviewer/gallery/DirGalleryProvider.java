/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.gallery;

import android.os.Process;
import android.support.annotation.Nullable;
import android.util.Log;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.image.Image;
import com.hippo.yorozuya.PriorityThread;
import com.hippo.yorozuya.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

public class DirGalleryProvider extends GalleryProvider implements Runnable {

    private static final String TAG = DirGalleryProvider.class.getSimpleName();
    private static final AtomicInteger sIdGenerator = new AtomicInteger();

    private final File mDir;
    private final Stack<Integer> mRequests = new Stack<>();
    @Nullable
    private Thread mBgThread;
    private volatile int mSize = STATE_WAIT;
    private String mError;
    private boolean mStarted = false;

    public DirGalleryProvider(File dir) {
        mDir = dir;
    }

    @Override
    public void start() {
        if (mStarted) {
            throw new IllegalStateException("Can't start it twice");
        }
        mStarted = true;

        if (mBgThread == null || mBgThread.isInterrupted()) {
            mBgThread = new PriorityThread(this, TAG + '-' + sIdGenerator.incrementAndGet(),
                    Process.THREAD_PRIORITY_BACKGROUND);
            mBgThread.start();
        }
    }

    @Override
    public void stop() {
        if (mBgThread == null || mBgThread.isInterrupted()) {
            return;
        }

        mBgThread.interrupt();
        mBgThread = null;
    }

    @Override
    public int size() {
        return mSize;
    }

    @Override
    @Result
    public int request(int index) {
        if (index < 0 || index >= size()) {
            return RESULT_ERROR;
        } else {
            synchronized (mRequests) {
                mRequests.add(index);
                mRequests.notify();
            }
            return RESULT_WAIT;
        }
    }

    @Override
    public String getError() {
        return mError;
    }

    @Override
    public void run() {
        // It may take a long time, so run it in new thread
        String[] files = mDir.list(new ImageFilter());

        if (files == null) {
            mSize = STATE_ERROR;
            mError = GetText.getString(R.string.error_not_folder_path);
            mBgThread = null;

            // Notify to to show error
            notifyDataChanged();

            Log.i(TAG, "ImageDecoder end with error");
            return;
        }

        // Sort it
        Arrays.sort(files);

        // Set state normal and notify
        mSize = files.length;
        notifyDataChanged();

        while (!Thread.currentThread().isInterrupted()) {
            int index;
            synchronized (mRequests) {
                if (mRequests.isEmpty()) {
                    try {
                        mRequests.wait();
                    } catch (InterruptedException e) {
                        // Interrupted
                        break;
                    }
                    continue;
                }
                index = mRequests.pop();
            }

            try {
                InputStream is = new FileInputStream(new File(mDir, files[index]));
                Image image = Image.decode(is, false);
                if (image != null) {
                    notifyPageSucceed(index, image);
                } else {
                    notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
                }
            } catch (FileNotFoundException e) {
                notifyPageFailed(index, GetText.getString(R.string.error_not_found));
            }
        }

        // Clear
        mBgThread = null;

        Log.i(TAG, "ImageDecoder end");
    }

    private static class ImageFilter implements FilenameFilter {

        @Override
        public boolean accept(File dir, String filename) {
            return StringUtils.endsWith(filename.toLowerCase(), SUPPORT_IMAGE_EXTENSIONS);
        }
    }
}