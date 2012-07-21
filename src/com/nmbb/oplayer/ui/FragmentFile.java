package com.nmbb.oplayer.ui;

import io.vov.vitamio.VIntent;
import io.vov.vitamio.provider.MediaStore.MediaColumns;
import io.vov.vitamio.provider.MediaStore.Video;

import java.io.File;
import java.util.ArrayList;
import com.nmbb.oplayer.R;
import com.nmbb.oplayer.po.PFile;
import com.nmbb.oplayer.receiver.IReceiverNotify;
import com.nmbb.oplayer.receiver.MediaScannerReceiver;
import com.nmbb.oplayer.ui.adapter.FileAdapter;
import com.nmbb.oplayer.ui.adapter.FileDownloadAdapter;
import com.nmbb.oplayer.ui.helper.FileDownloadHelper;
import com.nmbb.oplayer.util.FileUtils;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnTouchListener;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class FragmentFile extends FragmentBase implements LoaderCallbacks<Cursor>, OnItemClickListener, IReceiverNotify {
	private static final String TAG = "FragmentFile";
	private static final String[] PROJECTION_MEDIA = new String[] { Video.Media._ID, Video.Media.TITLE, Video.Media.TITLE_KEY, Video.Media.SIZE, Video.Media.DURATION, Video.Media.DATA, Video.Media.WIDTH, Video.Media.HEIGHT };
	private static final String ORDER_MEDIA_TITLE = Video.Media.TITLE_KEY + " COLLATE NOCASE ASC";

	private FileAdapter mAdapter;
	private FileDownloadAdapter mDownloadAdapter;
	private TextView first_letter_overlay;
	private ImageView alphabet_scroller;
	/** 临时列表 */
	private ListView mTempListView;

	private TextView mSDAvailable;
	/** 左下角进度显示 */
	private View mProgress;
	/** 记录ListView位置 */
	private int mVisiablePosition = 0;
	private int mVisiableTop = 0;

	private static final IntentFilter MEDIA_FILTER = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
	private MediaScannerReceiver mMediaScannerReceiver;
	static {
		MEDIA_FILTER.addAction(VIntent.ACTION_MEDIA_SCANNER_STARTED);
		MEDIA_FILTER.addAction(VIntent.ACTION_MEDIA_SCANNER_FINISHED);
	}
	/** 数据更改通知 */
	private long mNotifyTimestamp = 0;
	private final static long NOTIFY_INTERVAL = 2 * 1000 * 1000 * 1000;//2秒

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		getLoaderManager().initLoader(0, null, this);
		mAdapter = new FileAdapter(mParent, null);
		mMediaScannerReceiver = new MediaScannerReceiver(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View v = super.onCreateView(inflater, container, savedInstanceState);
		// ~~~~~~~~~ 绑定控件
		first_letter_overlay = (TextView) v.findViewById(R.id.first_letter_overlay);
		alphabet_scroller = (ImageView) v.findViewById(R.id.alphabet_scroller);
		mTempListView = (ListView) v.findViewById(R.id.templist);
		mSDAvailable = (TextView) v.findViewById(R.id.sd_block);
		mProgress = v.findViewById(android.R.id.progress);

		// ~~~~~~~~~ 绑定事件
		alphabet_scroller.setClickable(true);
		alphabet_scroller.setOnTouchListener(asOnTouch);
		mListView.setOnItemClickListener(this);
		mTempListView.setOnItemClickListener(this);
		mListView.setOnCreateContextMenuListener(OnListViewMenu);
		mTempListView.setOnCreateContextMenuListener(OnTempListViewMenu);

		// ~~~~~~~~~ 加载数据
		mListView.setAdapter(mAdapter);

		Log.e(TAG, "onCreateView");
		return v;
	}

	private ContentObserver mDataChangeObserver = new ContentObserver(new Handler()) {
		@Override
		public void onChange(boolean selfChange) {
			super.onChange(selfChange);
			long time = System.nanoTime();
			//Log.e(TAG, "ContentObserver " + time);
			if (time - mNotifyTimestamp > NOTIFY_INTERVAL) {
				mNotifyTimestamp = time;
				mHandler.sendEmptyMessage(MSG_REFRESH);
			}
		}
	};

	private static final int MSG_REFRESH = 0;
	/** 检测是否扫描完毕 */
	private static final int MSG_SCAN = 1;

	private Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch (msg.what) {
			case MSG_REFRESH:
				if (!mHandler.hasMessages(MSG_SCAN))
					mHandler.sendEmptyMessageDelayed(MSG_SCAN, 5000);//5秒后检查是否还在扫描
				refresh();
				break;
			case MSG_SCAN:
				Log.e(TAG, "mParent:" + (mParent == null));
				if (mParent != null) {
					boolean isScanning = MediaScannerReceiver.isScanning(mParent);
					Log.e(TAG, "isScanning:" + isScanning);
					if (mParent != null && !isScanning) {
						mProgress.setVisibility(View.GONE);
					}
				}
				break;
			}
		}
	};

	/** 接收扫描完毕事件 */
	@Override
	public void receiver(int flag) {
		Log.e(TAG, "receiver " + flag);
		if (flag == 0) {
			//开始扫描
			mProgress.setVisibility(View.VISIBLE);
		} else if (flag == 2) {
			//扫描完成
			mProgress.setVisibility(View.GONE);
			refresh();
		}
	}

	@Override
	public void onResume() {
		super.onResume();

		registerReceiver(mMediaScannerReceiver, MEDIA_FILTER);
		registerContentObserver(Video.Media.CONTENT_URI, true, mDataChangeObserver);

		//检测是否正在扫描
		if (mParent != null && MediaScannerReceiver.isScanning(mParent)) {
			mProgress.setVisibility(View.VISIBLE);
		}

		refresh();

		//SD卡剩余数量
		mSDAvailable.setText(FileUtils.showFileAvailable());
	}

	@Override
	public void onPause() {
		super.onPause();

		unregisterReceiver(mMediaScannerReceiver);
		unregisterContentObserver(mDataChangeObserver);
	}

	/** 刷新数据 */
	private void refresh() {
		mVisiablePosition = mListView.getFirstVisiblePosition();
		if (mListView.getChildCount() > 0) {
			View v = mListView.getChildAt(0);
			mVisiableTop = (v == null) ? 0 : v.getTop();
		} else {
			mVisiableTop = 0;
		}
		mListView.setAdapter(mAdapter);
		getLoaderManager().restartLoader(0, null, this);
	}

	@Override
	public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
		Log.e(TAG, "onCreateLoader");
		return new CursorLoader(mParent, Video.Media.CONTENT_URI, PROJECTION_MEDIA, null, null, ORDER_MEDIA_TITLE);
	}

	@Override
	public void onLoadFinished(Loader<Cursor> arg0, Cursor newCursor) {
		Log.e(TAG, "onLoadFinished" + newCursor.getCount());
		mAdapter.swapCursor(newCursor);
		mListView.setSelectionFromTop(mVisiablePosition, mVisiableTop);
	}

	@Override
	public void onLoaderReset(Loader<Cursor> arg0) {
		Log.e(TAG, "onLoaderReset");
		mAdapter.swapCursor(null);
	}

	ListView.OnCreateContextMenuListener OnListViewMenu = new ListView.OnCreateContextMenuListener() {
		@Override
		public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
			menu.setHeaderTitle(R.string.file_oper);
			menu.add(0, 0, 0, R.string.file_rename);//重命名
			menu.add(0, 1, 0, R.string.file_delete);//删除
		}
	};

	ListView.OnCreateContextMenuListener OnTempListViewMenu = new ListView.OnCreateContextMenuListener() {

		@Override
		public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
			menu.setHeaderTitle(R.string.file_oper);
			menu.add(0, 2, 0, R.string.file_delete);//删除
		}
	};

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		ContextMenuInfo info = item.getMenuInfo();
		AdapterView.AdapterContextMenuInfo contextMenuInfo = (AdapterView.AdapterContextMenuInfo) info;
		int position = contextMenuInfo.position;
		switch (item.getItemId()) {
		case 0:
			renameFile(mAdapter, mAdapter.getItem(position), position);
			break;
		case 1:
			deleteFile(mAdapter, mAdapter.getItem(position), position);
			break;
		case 2:
			deleteFile(mDownloadAdapter, mDownloadAdapter.getItem(position), position);
			break;
		}
		return super.onContextItemSelected(item);
	};

	/** 删除文件 */
	private void deleteFile(final BaseAdapter adapter, final PFile f, final int position) {
		new AlertDialog.Builder(getActivity()).setIcon(android.R.drawable.ic_dialog_alert).setTitle(R.string.file_delete).setMessage(getString(R.string.file_delete_confirm, f.title)).setNegativeButton(android.R.string.yes, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				try {
					File file = new File(f.path);
					if (file.canRead() && file.exists()) {
						file.delete();
					}
					if (f._id > 0) {
						getActivity().getContentResolver().delete(ContentUris.withAppendedId(Video.Media.CONTENT_URI, f._id), null, null);
						refresh();
					}
				} catch (Exception e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}

		}).setPositiveButton(android.R.string.no, null).show();
	}

	/** 重命名文件 */
	private void renameFile(final BaseAdapter adapter, final PFile f, final int position) {
		final EditText et = new EditText(getActivity());
		et.setText(f.title);
		new AlertDialog.Builder(getActivity()).setTitle(R.string.file_rename).setIcon(android.R.drawable.ic_dialog_info).setView(et).setNegativeButton(android.R.string.yes, new DialogInterface.OnClickListener() {

			@Override
			public void onClick(DialogInterface dialog, int which) {
				String name = et.getText().toString().trim();
				if (name == null || name.trim().equals("") || name.trim().equals(f.title))
					return;

				try {
					File fromFile = new File(f.path);
					File nf = new File(fromFile.getParent(), name.trim());
					if (nf.exists()) {
						Toast.makeText(getActivity(), R.string.file_rename_exists, Toast.LENGTH_LONG).show();
					} else if (fromFile.renameTo(nf)) {
						//更新库
						if (f._id > 0) {
							ContentValues values = new ContentValues();
							values.put(MediaColumns.DATA, nf.getPath());
							values.put(MediaColumns.TITLE, name);
							getActivity().getContentResolver().update(ContentUris.withAppendedId(Video.Media.CONTENT_URI, f._id), values, null, null);

							refresh();
						}
					}
				} catch (SecurityException se) {
					Toast.makeText(getActivity(), R.string.file_rename_failed, Toast.LENGTH_LONG).show();
				}
			}

		}).setPositiveButton(android.R.string.no, null).show();
	}

	public Handler mDownloadHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			PFile p;
			String url = msg.obj.toString();
			switch (msg.what) {
			case FileDownloadHelper.MESSAGE_START://开始下载
				p = new PFile();
				p.path = mParent.mFileDownload.mDownloadUrls.get(url);
				p.title = new File(p.path).getName();
				p.status = 0;
				p.file_size = 0;
				if (mDownloadAdapter == null) {
					mDownloadAdapter = new FileDownloadAdapter(getActivity(), new ArrayList<PFile>());
					mDownloadAdapter.add(p, url);
					mTempListView.setAdapter(mDownloadAdapter);
					mTempListView.setVisibility(View.VISIBLE);
				} else {
					mDownloadAdapter.add(p, url);
					mDownloadAdapter.notifyDataSetChanged();
				}
				break;
			case FileDownloadHelper.MESSAGE_PROGRESS://正在下载
				p = mDownloadAdapter.getItem(url);
				p.temp_file_size = msg.arg1;
				p.file_size = msg.arg2;
				int status = (int) ((msg.arg1 * 1.0 / msg.arg2) * 10);
				if (status > 10)
					status = 10;
				p.status = status;
				mDownloadAdapter.notifyDataSetChanged();
				break;
			case FileDownloadHelper.MESSAGE_STOP://下载结束
				p = mDownloadAdapter.getItem(url);
				//通知有新的视频文件
				getActivity().sendBroadcast(new Intent(VIntent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse(url)));
				break;
			case FileDownloadHelper.MESSAGE_ERROR:
				Toast.makeText(getActivity(), url, Toast.LENGTH_LONG).show();
				break;
			}
			super.handleMessage(msg);
		}
	};

	/** 单击启动播放 */
	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		final PFile f = parent == mListView ? mAdapter.getItem(position) : mDownloadAdapter.getItem(position);
		Intent intent = new Intent(getActivity(), VideoPlayerActivity.class);
		intent.putExtra("path", f.path);
		intent.putExtra("title", f.title);
		startActivity(intent);
	}

	/**
	 * A-Z
	 */
	private OnTouchListener asOnTouch = new OnTouchListener() {

		@Override
		public boolean onTouch(View v, MotionEvent event) {
			switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:// 0
				alphabet_scroller.setPressed(true);
				first_letter_overlay.setVisibility(View.VISIBLE);
				mathScrollerPosition(event.getY());
				break;
			case MotionEvent.ACTION_UP:// 1
				alphabet_scroller.setPressed(false);
				first_letter_overlay.setVisibility(View.GONE);
				break;
			case MotionEvent.ACTION_MOVE:
				mathScrollerPosition(event.getY());
				break;
			}
			return false;
		}
	};

	/**
	 * 显示字符
	 * 
	 * @param y
	 */
	private void mathScrollerPosition(float y) {
		int height = alphabet_scroller.getHeight();
		float charHeight = height / 28.0f;
		char c = 'a';
		if (y < 0)
			y = 0;
		else if (y > height)
			y = height;

		int index = (int) (y / charHeight) - 1;
		if (index < 0)
			index = 0;
		else if (index > 25)
			index = 25;

		c = (char) (c + index);
		char text = (char) (c - 32);
		first_letter_overlay.setText(text + "");

		if (index == 0)
			mListView.setSelection(0);
		else if (index == 25)
			mListView.setSelection(mAdapter.getCount() - 1);
		else {
			int postion = mAdapter.getPositionByName(c);
			if (postion > 0) {
				mListView.setSelection(postion);
			}
		}
	}
}
