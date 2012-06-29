package com.nmbb.oplayer.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import com.nmbb.oplayer.R;
import com.nmbb.oplayer.business.FileBusiness;
import com.nmbb.oplayer.database.SQLiteHelper;
import com.nmbb.oplayer.po.PFile;
import com.nmbb.oplayer.ui.base.ArrayAdapter;
import com.nmbb.oplayer.ui.helper.FileDownloadHelper;
import com.nmbb.oplayer.util.FileUtils;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnTouchListener;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class FragmentFile extends FragmentBase implements OnItemClickListener {

	private FileAdapter mAdapter;
	private FileAdapter mDownloadAdapter;
	private TextView first_letter_overlay;
	private ImageView alphabet_scroller;
	/** 临时列表 */
	private ListView mTempListView;
	private MainFragmentActivity mParent;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View v = super.onCreateView(inflater, container, savedInstanceState);
		// ~~~~~~~~~ 绑定控件
		first_letter_overlay = (TextView) v.findViewById(R.id.first_letter_overlay);
		alphabet_scroller = (ImageView) v.findViewById(R.id.alphabet_scroller);
		mTempListView = (ListView) v.findViewById(R.id.templist);

		// ~~~~~~~~~ 绑定事件
		alphabet_scroller.setClickable(true);
		alphabet_scroller.setOnTouchListener(asOnTouch);
		mListView.setOnItemClickListener(this);
		mTempListView.setOnItemClickListener(this);

		// ~~~~~~~~~ 加载数据
		mParent = (MainFragmentActivity) getActivity();
		if (new SQLiteHelper(getActivity()).isEmpty())
			new ScanVideoTask().execute();
		else
			new DataTask().execute();

		return v;
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
					mDownloadAdapter = new FileAdapter(getActivity(), new ArrayList<PFile>());
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
				FileBusiness.insertFile(getActivity(), p);
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
		startActivity(intent);
	}

	private class DataTask extends AsyncTask<Void, Void, ArrayList<PFile>> {

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			mLoadingLayout.setVisibility(View.VISIBLE);
			mListView.setVisibility(View.GONE);
		}

		@Override
		protected ArrayList<PFile> doInBackground(Void... params) {
			return FileBusiness.getAllSortFiles(getActivity());
		}

		@Override
		protected void onPostExecute(ArrayList<PFile> result) {
			super.onPostExecute(result);

			mAdapter = new FileAdapter(getActivity(), FileBusiness.getAllSortFiles(getActivity()));
			mListView.setAdapter(mAdapter);

			mLoadingLayout.setVisibility(View.GONE);
			mListView.setVisibility(View.VISIBLE);
		}
	}

	/** 扫描SD卡 */
	private class ScanVideoTask extends AsyncTask<Void, File, ArrayList<PFile>> {
		private ProgressDialog pd;
		private ArrayList<File> files = new ArrayList<File>();

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			pd = new ProgressDialog(getActivity());
			pd.setMessage("正在扫描视频文件...");
			pd.show();
		}

		@Override
		protected ArrayList<PFile> doInBackground(Void... params) {
			// ~~~ 遍历文件夹
			eachAllMedias(Environment.getExternalStorageDirectory());

			// ~~~ 入库
			FileBusiness.batchInsertFiles(getActivity(), files);

			// ~~~ 查询数据
			return FileBusiness.getAllSortFiles(getActivity());
		}

		@Override
		protected void onProgressUpdate(final File... values) {
			pd.setMessage(values[0].getName());
		}

		/** 遍历所有文件夹，查找出视频文件 */
		public void eachAllMedias(File f) {
			if (f != null && f.exists() && f.isDirectory()) {
				File[] files = f.listFiles();
				if (files != null) {
					for (File file : f.listFiles()) {
						publishProgress(file);
						if (file.isDirectory()) {
							eachAllMedias(file);
						} else if (file.exists() && file.canRead() && FileUtils.isVideo(file)) {
							this.files.add(file);
						}
					}
				}
			}
		}

		@Override
		protected void onPostExecute(ArrayList<PFile> result) {
			super.onPostExecute(result);
			mAdapter = new FileAdapter(getActivity(), result);
			mListView.setAdapter(mAdapter);
			pd.dismiss();
		}
	}

	private class FileAdapter extends ArrayAdapter<PFile> {

		private HashMap<String, PFile> maps = new HashMap<String, PFile>();

		public FileAdapter(Context ctx, ArrayList<PFile> l) {
			super(ctx, l);
			maps.clear();
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			final PFile f = getItem(position);
			if (convertView == null) {
				final LayoutInflater mInflater = getActivity().getLayoutInflater();
				convertView = mInflater.inflate(R.layout.fragment_file_item, null);
			}
			((TextView) convertView.findViewById(R.id.title)).setText(f.title);
			
			//显示文件大小
			String file_size ;
			if (f.temp_file_size > 0) {
				file_size = FileUtils.showFileSize(f.temp_file_size) + " / " +FileUtils.showFileSize(f.file_size);
			} else {
				file_size = FileUtils.showFileSize(f.file_size);
			}
			((TextView) convertView.findViewById(R.id.file_size)).setText(file_size);
			
			//显示进度表
			final ImageView status = (ImageView) convertView.findViewById(R.id.status);
			if (f.status > -1) {
				int resStauts = getStatusImage(f.status);
				if (resStauts > 0) {
					if (status.getVisibility() != View.VISIBLE)
						status.setVisibility(View.VISIBLE);
					status.setImageResource(resStauts);
				}
			} else {
				if (status.getVisibility() != View.GONE)
					status.setVisibility(View.GONE);
			}
			return convertView;
		}

		public void add(PFile item, String url) {
			super.add(item);
			if (!maps.containsKey(url))
				maps.put(url, item);
		}

		public PFile getItem(String url) {
			return maps.get(url);
		}
	}

	private int getStatusImage(int status) {
		int resStauts = -1;
		switch (status) {
		case 0:
			resStauts = R.drawable.down_btn_0;
			break;
		case 1:
			resStauts = R.drawable.down_btn_1;
			break;
		case 2:
			resStauts = R.drawable.down_btn_2;
			break;
		case 3:
			resStauts = R.drawable.down_btn_3;
			break;
		case 4:
			resStauts = R.drawable.down_btn_4;
			break;
		case 5:
			resStauts = R.drawable.down_btn_5;
			break;
		case 6:
			resStauts = R.drawable.down_btn_6;
			break;
		case 7:
			resStauts = R.drawable.down_btn_7;
			break;
		case 8:
			resStauts = R.drawable.down_btn_8;
			break;
		case 9:
			resStauts = R.drawable.down_btn_9;
			break;
		case 10:
			resStauts = R.drawable.down_btn_10;
			break;
		}
		return resStauts;
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
		char c = 'A';
		if (y < 0)
			y = 0;
		else if (y > height)
			y = height;

		int index = (int) (y / charHeight) - 1;
		if (index < 0)
			index = 0;
		else if (index > 25)
			index = 25;

		String key = String.valueOf((char) (c + index));
		first_letter_overlay.setText(key);

		int position = 0;
		if (index == 0)
			mListView.setSelection(0);
		else if (index == 25)
			mListView.setSelection(mAdapter.getCount() - 1);
		else {
			if (mAdapter != null && mAdapter.getAll() != null) {
				for (PFile item : mAdapter.getAll()) {
					if (item.title_pinyin.startsWith(key)) {
						mListView.setSelection(position);
						break;
					}
					position++;
				}
			}
		}
	}
}
