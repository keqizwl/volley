/**
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.volley.toolbox;

import android.R;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.AnimationUtils;

import com.android.volley.Request.SourceType;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader.ImageContainer;
import com.android.volley.toolbox.ImageLoader.ImageListener;

/**
 * Handles fetching an image from a URL as well as the life-cycle of the
 * associated request.
 */
public class NetworkImageView extends RecyclingImageView {
	public interface OnLoadBitmapFinishListener {
		public void onloadBitmapFinish(boolean isSuccess);
	}

	private static final String Tag = "NetworkImageView";

	/** The URL of the network image to load */
	private String mUrl;

	/**
	 * @Fields where the image source from
	 */
	private int mSourceType;

	/**
	 * if force load the imagesource
	 */
	private boolean mIsForce;

	/**
	 * if load the image source success
	 */
	private boolean loadSuccess;

	/**
	 * Resource ID of the image to be used as a placeholder until the network
	 * image is loaded.
	 */

	private int mDefaultImageId;

	/**
	 * Resource ID of the image to be used if the network response fails.
	 */
	private int mErrorImageId;

	/** Local copy of the ImageLoader. */
	private ImageLoader mImageLoader;
	/** Current ImageContainer. (either in-flight or finished) */
	private ImageContainer mImageContainer;
	private boolean ifOutOfMemory = false;
	private OnLoadBitmapFinishListener loadBitmapFinishListener;

	public NetworkImageView(Context context) {
		this(context, null);
	}

	public NetworkImageView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public NetworkImageView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	/**
	 * Sets URL of the image that should be loaded into this view. Note that
	 * calling this will immediately either set the cached image (if available)
	 * or the default image specified by
	 * {@link NetworkImageView#setDefaultImageResId(int)} on the view.
	 * 
	 * NOTE: If applicable, {@link NetworkImageView#setDefaultImageResId(int)}
	 * and {@link NetworkImageView#setErrorImageResId(int)} should be called
	 * prior to calling this function.
	 * 
	 * @param url
	 *            The URL that should be loaded into this ImageView.
	 * @param sourceType
	 *            The sourceType of mUrl.
	 * @param imageLoader
	 *            ImageLoader that will be used to make the request.
	 * @param isForce
	 *            if force load imageSource ,if true ,clear cache
	 */
	public void setImageUrl(String url, int sourceType,
			ImageLoader imageLoader, boolean isForce) {
		mIsForce = isForce;
		loadLocal = false;

		mUrl = url;
		mSourceType = sourceType;
		mImageLoader = imageLoader;
		loadImageIfNecessary(false);
	}

	public void setImageUrl(String url, int sourceType, ImageLoader imageLoader) {
		setImageUrl(url, sourceType, imageLoader, false);
	}

	public void setImageUrl(String url, ImageLoader imageLoader) {
		setImageUrl(url, SourceType.SOURCE_TYPE_WEB, imageLoader, false);
	}

	/**
	 * Sets the default image resource ID to be used for this view until the
	 * attempt to load it completes.
	 */
	public void setDefaultImageResId(int defaultImage) {
		mDefaultBitmap = null;
		mDefaultImageId = defaultImage;
	}

	/**
	 * Sets the error image resource ID to be used for this view in the event
	 * that the image requested fails to load.
	 */
	public void setErrorImageResId(int errorImage) {
		mErrorImageId = errorImage;
	}

	/**
	 * Loads the image for the view if it isn't already loaded.
	 * 
	 * @param isInLayoutPass
	 *            True if this was invoked from a layout pass, false otherwise.
	 */
	private void loadImageIfNecessary(final boolean isInLayoutPass) {
		boolean isForce = mIsForce;
		mIsForce = false;

		int width = getWidth();
		int height = getHeight();

		boolean isFullyWrapContent = getLayoutParams() != null
				&& getLayoutParams().height == LayoutParams.WRAP_CONTENT
				&& getLayoutParams().width == LayoutParams.WRAP_CONTENT;
		// if the view's bounds aren't known yet, and this is not a
		// wrap-content/wrap-content
		// view, hold off on loading the image.
		if (width == 0 && height == 0 && !isFullyWrapContent) {
			return;
		}

		// if the URL to be loaded in this view is empty, cancel any old
		// requests and clear the
		// currently loaded image.
		if (TextUtils.isEmpty(mUrl)) {
			cancelRequestAndSetDefaultBitmap();
			return;
		} else if (mSourceType == SourceType.SOURCE_TYPE_WEB) {
			if (Uri.parse(mUrl) == null || Uri.parse(mUrl).getHost() == null) {
				cancelRequestAndSetDefaultBitmap();
				return;
			}
		}
		// if not force and there was an old request in this view, check if it
		// needs to be
		// canceled.
		if (!isForce) {
			if (mImageContainer != null
					&& mImageContainer.getRequestUrl() != null) {
				if (mImageContainer.getRequestUrl().equals(mUrl)
						&& !ifOutOfMemory) {
					return;
				} else {
					cancelRequestAndSetDefaultBitmap();
				}
			}
		}

		// The pre-existing content of this view didn't match the current URL.
		// Load the new image
		// from the network.
		ifOutOfMemory = false;
		loadSuccess = false;
		ImageContainer newContainer = mImageLoader.get(mUrl, mSourceType,
				new ImageListener() {
					@Override
					public void onErrorResponse(VolleyError error) {
						if (error.getCause() instanceof OutOfMemoryError) {
							ifOutOfMemory = true;
						}

						if (loadLocal) {
							return;
						}
						if (mErrorImageId != 0) {
							setImageResource(mErrorImageId);
						} else {
							setDefaultBitmap();
						}
					}

					@Override
					public void onResponse(final ImageContainer response,
							boolean isImmediate) {
						// If this was an immediate response that was delivered
						// inside of a layout
						// pass do not set the image immediately as it will
						// trigger a requestLayout
						// inside of a layout. Instead, defer setting the image
						// by posting back to
						// the main thread.
						ifOutOfMemory = false;

						if (loadLocal) {
							return;
						}

						if (isImmediate && isInLayoutPass) {
							post(new Runnable() {
								@Override
								public void run() {
									onResponse(response, false);
								}
							});
							return;
						}

						loadSuccess = (response.getBitmapDrawable() != null);

						if (loadBitmapFinishListener != null) {
							loadBitmapFinishListener
									.onloadBitmapFinish(loadSuccess);
						}

						if (loadSuccess) {
							setImageDrawable(response.getBitmapDrawable());
							if (!isImmediate) {
								startAnimation(AnimationUtils.loadAnimation(
										getContext(), R.anim.fade_in));
							}
						} else {
							setDefaultBitmap();
						}
					}
				}, width, height, isForce);
		mImageContainer = newContainer;
	}

	private void cancelRequestAndSetDefaultBitmap() {
		if (mImageContainer != null) {
			mImageContainer.cancelRequest();
			mImageContainer = null;
		}
		setDefaultBitmap();
	}

	private void setDefaultBitmap() {
		if (mDefaultBitmap != null && !mDefaultBitmap.isRecycled()) {
			setImageBitmap(mDefaultBitmap);
		} else if (mDefaultImageId != 0) {
			setImageResource(mDefaultImageId);
		} else {
			setImageBitmap(null);
		}
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
		super.onLayout(changed, left, top, right, bottom);
		if (!loadLocal && !loadSuccess) {
			loadImageIfNecessary(true);
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		if (mImageContainer != null) {
			// If the view was bound to an image request, cancel it and clear
			// out the image from the view.
			mImageContainer.cancelRequest();
			// also clear out the container so we can reload the image if
			// necessary.
			mImageContainer = null;
			mDefaultBitmap = null;
		}
		super.onDetachedFromWindow();
	}

	private Bitmap mDefaultBitmap;

	public void setDefaultBitmap(Bitmap bitmap) {
		mDefaultImageId = 0;
		if (bitmap != null && !bitmap.isRecycled()) {
			mDefaultBitmap = bitmap;
		}
	}

	private boolean loadLocal = false;

	@Override
	protected void drawableStateChanged() {
		super.drawableStateChanged();
		invalidate();
	}

	public void setImageBitmapOut(Bitmap bm) {
		loadLocal = true;
		stopRequestAndClear();
		super.setImageBitmap(bm);
	}

	public void setImageResourceOut(int resId) {
		loadLocal = true;
		stopRequestAndClear();
		super.setImageResource(resId);
	}

	private void stopRequestAndClear() {
		if (mImageContainer != null) {
			mImageContainer.cancelRequest();
			mImageContainer = null;
		}
		mUrl = null;
	}

	public void setLoadBitmapFinishListener(
			OnLoadBitmapFinishListener loadBitmapFinishListener) {
		this.loadBitmapFinishListener = loadBitmapFinishListener;
	}
}
