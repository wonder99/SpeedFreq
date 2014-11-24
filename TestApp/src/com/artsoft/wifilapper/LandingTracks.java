// Copyright 2011-2012, Art Hare
// This file is part of WifiLapper.

//WifiLapper is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.

//WifiLapper is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.

//You should have received a copy of the GNU General Public License
//along with WifiLapper.  If not, see <http://www.gnu.org/licenses/>.

package com.artsoft.wifilapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;




import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class LandingTracks extends ListActivity implements OnCancelListener, OnDismissListener, Handler.Callback 
{
	private RaceImageFactory m_imgFactory=null;
	private Handler m_handler;
	private Context m_context;
	private static final int MSG_NEW_IMAGE = 151;
	private static final int MSG_DOWNLOAD_COMPLETE = 252;
	private static final int MSG_UPLOAD_COMPLETE = 254;
	private static final int MSG_EMAIL_COMPLETE = 253;

	private static final int TRACK_START_NEW = -111;
	private static final int TRACK_DOWNLOAD = -112;
	private static final int TRACK_UPLOAD = -113;
	private static final int TRACK_UPLOAD_ALL = -1;
	private ListView list;
	String strUploadDB;

	File FileToSend=null;
	
	private ProgressDialog barProgressDialog;
	RaceDatabase.download myDnld=null;
	RaceDatabase.upload myUpld=null;
	
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		m_handler = new Handler(this);
		m_context = this;
//		strUploadDB = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + "/upload.tracks";
		strUploadDB = Environment.getExternalStorageDirectory().toString() + "/speedfreq/upload.tracks";

		
		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setContentView(R.layout.landingtracks);
	}
	@Override
	public void onResume()
	{
		super.onResume();
		DoUIInit();
	}
	// Don't bother with onPause, since we only want to kill the image thread and store the 
	// track preference when we actually exit this activity
	// killing the imagefactory in onpause doesn't help either
	public void onDestroy()
	{
		super.onDestroy();
		if( m_imgFactory != null) {
			m_imgFactory.Shutdown();
			m_imgFactory = null;
		}
		// Doesn't seem involved in the crash
		if( FileToSend != null) {
			File JournalFile = new File(FileToSend.getPath()+"-journal");
			if( FileToSend.exists() ) 
				FileToSend.delete();
			if( JournalFile.exists() )
				JournalFile.delete();
		}
		
		// check if selected track is still in list, return -1 if not
		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
		int track = settings.getInt(Prefs.PREF_TRACK_ID, Prefs.DEFAULT_TRACK_ID_INT);
		ListTrackData lrd;
		boolean bFound = false;
		for( int i=0; i<list.getCount(); i++ )
		{
			lrd = (ListTrackData)list.getItemAtPosition(i);
			if( lrd.id == track )
				bFound = true;
		}
		if( !bFound )
			settings.edit().putInt(Prefs.PREF_TRACK_ID, -1).commit();
		
	}
	private void DoUIInit()
	{
		// Hide keyboard by default
//		getWindow().setSoftInputMode(
//			      WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

		list = (ListView)findViewById(android.R.id.list);

		// Only create a new image factory if it doesn't already exist
		if( m_imgFactory == null )
			m_imgFactory = new RaceImageFactory(m_handler, MSG_NEW_IMAGE, true);

		FillTrackData(list);
	}
	
	private static class ListTrackData
	{
		private String strTrackName;
		private int id;
		public ListTrackData(String strTrackName, int cLaps, int id, float laptime, int iStartTime, boolean fUsePointToPoint, int iFinishCount)
		{
			this.strTrackName = strTrackName;
			this.id = id;
		}
	}

    private class LoadRaceAdapter extends ArrayAdapter<ListTrackData>
	{
	    private List<ListTrackData> items;

	    public LoadRaceAdapter(Context context, List<ListTrackData> objects) 
	    {
	        super(context, R.id.txtRaceName, objects);

	        this.items = objects;
	    }

	    @SuppressLint("InflateParams")
		@Override
	    public View getView(int position, View convertView, ViewGroup parent) 
	    {
	    	View v = convertView;

	    	if (v == null) {
	    		LayoutInflater vi = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
	    		v = vi.inflate(R.layout.track_item, null);
	    	}
	    	ListTrackData myobject = items.get(position);
	    	if (myobject!=null)
	    	{
	    		ImageView img = (ImageView)v.findViewById(R.id.imgRace);
	    		TextView txtName = (TextView)v.findViewById(R.id.txtRaceName);

	    		switch( myobject.id ) {
	    		case TRACK_START_NEW:
	    			txtName.setGravity(Gravity.CENTER);
	    			txtName.setPadding(0, 15, 0, 15);
	    			txtName.setText("Start a new track");
	    			img.setImageBitmap(null);
	    			break;
	    		case TRACK_DOWNLOAD:
	    			txtName.setGravity(Gravity.CENTER);
	    			txtName.setPadding(0, 15, 0, 15);
	    			txtName.setText("Download Tracks");
	    			img.setImageBitmap(null);
	    			break;
	    		case TRACK_UPLOAD:
	    			txtName.setGravity(Gravity.CENTER);
	    			txtName.setPadding(0, 15, 0, 15);
	    			txtName.setText("Upload all tracks");
	    			img.setImageBitmap(null);
	    			break;
	    		default:
	    			txtName.setGravity(Gravity.LEFT);
	    			txtName.setText(myobject.strTrackName);
	    			int size;
	    			if( parent.getHeight() > parent.getWidth() )
	    				size = parent.getWidth()/3;	
	    			else
	    				size = parent.getHeight()/4;	
	    			img.setImageBitmap(m_imgFactory.GetImage(myobject.id, size,size,false));
	    		}
	    	}
    		return v;
	    }
	}
    private void FillTrackData(ListView list) 
	{	
    	SQLiteDatabase db = RaceDatabase.Get();
//    	if( db == null )
//    		return;
//    	
		Cursor cursor = RaceDatabase.GetTrackList(db);

		if(cursor == null)
		{
			Toast.makeText(this, "Your database appears to have become corrupt, possibly due to developer error.  Try switching to SD card in options.  For more and better options, go to wifilapper.freeforums.org", Toast.LENGTH_LONG).show();
			return;
		}
		List<ListTrackData> lstTrackData = new ArrayList<ListTrackData>();
		
		// This will be for the 'define new track'
		lstTrackData.add(new ListTrackData("dummy_entry", 0, TRACK_START_NEW, 0,0,false,0));

		while(cursor.moveToNext())
		{
			String strRaceName = cursor.getString(1);
			int id = cursor.getInt(0);
			
			lstTrackData.add(new ListTrackData(strRaceName, 0, id, 0,0,false,0));
		}
		cursor.close();
		
		// This will be for the 'download tracks'
		lstTrackData.add(new ListTrackData("dummy_entry", 0, TRACK_DOWNLOAD, 0,0,false,0));
		// This will be for the 'upload all tracks'
		lstTrackData.add(new ListTrackData("dummy_entry", 0, TRACK_UPLOAD, 0,0,false,0));
		
		ArrayAdapter<ListTrackData> adapter = new LoadRaceAdapter(this, lstTrackData);
		
		list.setAdapter(adapter);
		registerForContextMenu(list);
		
	}
	public void onCreateContextMenu (ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo)
	{
		if(v == findViewById(android.R.id.list))
		{
			// ok, they've contextmenu'd on the race selection list.  We want to show the "delete/rename" menu
	    	MenuInflater inflater = getMenuInflater();
	    	inflater.inflate(R.menu.trackoptions, menu);
		}
	}
	
	@Override
	public boolean onContextItemSelected(MenuItem item)
	{
		// Get ID of selected track
		AdapterContextMenuInfo info = (AdapterContextMenuInfo)item.getMenuInfo();
		ListView list = (ListView)info.targetView.getParent();
		ListTrackData lrd = (ListTrackData)list.getItemAtPosition(info.position);

		if(item.getItemId() == R.id.mnuDelete)
		{
			// they have requested that we delete the selected race
			RaceDatabase.DeleteTrack(RaceDatabase.Get(), lrd.id);
			FillTrackData(list);
			return true;
		}
		else if(item.getItemId() == R.id.mnuRename)
		{
			// they have requested that we rename the selected race
			Dialog d = new LandingRaceBase.RenameDialog<ListTrackData>(this, "Set the new track name", lrd, lrd.strTrackName, R.id.edtRename);
			d.setOnDismissListener(this);
			d.show();
			
			return true;
		}
		else if(item.getItemId() == R.id.mnuEmail)
		{
    		uploadTracks(lrd.id);
    		return true;
//			return sendByEmail(false,lrd.id);
		}
		
		return false;
	}


  	@Override
 
	public void onActivityResult(int reqCode, int resCode, Intent data)
	{
		if(reqCode == MSG_EMAIL_COMPLETE)
		{
			// Clean up the temp file used for uploading
//			if( FileToSend != null ) {
//				File JournalFile = new File(FileToSend.getPath()+"-journal");
//				if( JournalFile.exists() )
//					JournalFile.delete();
//				if( FileToSend.exists() )
//					FileToSend.delete();
//			}
		}
	}
	

	@Override
	public void onDismiss(DialogInterface arg0) 
	{
		if(arg0.getClass().equals(LandingRaceBase.RenameDialog.class))
		{
			@SuppressWarnings("unchecked")
			LandingRaceBase.RenameDialog<ListTrackData> rd = (LandingRaceBase.RenameDialog<ListTrackData>)arg0;
			if(!rd.WasCancelled())
			{
				if(rd.GetParam() == R.id.edtRename)
				{
					// it was shown for the rename purpose
					ListTrackData lrd = rd.GetData();
					String strNewRacename = rd.GetResultText();
					
					RaceDatabase.RenameTrack(RaceDatabase.Get(), lrd.id, strNewRacename);
					
					ListView list = (ListView)findViewById(android.R.id.list);
					FillTrackData(list);
				}
			}
		}
	}

//	@Override
//	public void onClick(View v) {
//		// TODO Auto-generated method stub
//		
//	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id)
	{
		ListTrackData myObject = (ListTrackData) l.getItemAtPosition(position);

		switch( myObject.id )
		{
		case TRACK_DOWNLOAD:
			downloadTracks();
			break;
		case TRACK_UPLOAD:
    		uploadTracks(TRACK_UPLOAD_ALL);
//			sendByEmail(false,this);
			break;
		default:
			// Get pointer to selected Track Data
			ListTrackData lrd = (ListTrackData)l.getItemAtPosition(position);

			// Store the selected track ID in the preferences
			SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
			settings.edit().putInt(Prefs.PREF_TRACK_ID, (int)lrd.id).commit();
			// Exit activity on this selection
			finish();
		}
	}


	@Override
    public boolean onCreateOptionsMenu(Menu menu) 
    {
    	MenuInflater inflater = getMenuInflater();
    	inflater.inflate(R.menu.tracksmenu, menu);
    	return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menu)
    {
    	if(menu.getItemId() == R.id.mnuDeleteAllTracks)
    	{
    		RaceDatabase.DeleteAllTracks(RaceDatabase.Get());
    		DoUIInit();
    		return true;
    	}
    	if(menu.getItemId() == R.id.mnuEmail)
    	{
    		uploadTracks(TRACK_UPLOAD_ALL);
    		return true;
    	}
    	
    	if(menu.getItemId() == R.id.mnuGetFile)
    	{
    		downloadTracks();
    		return true;
    	}

    	return super.onOptionsItemSelected(menu);
    }
    private void uploadTracks(int iTrackCode) 
    {
    	barProgressDialog = new ProgressDialog(this);

    	barProgressDialog.setMessage("Lauching Mail App...");

    	barProgressDialog.setIndeterminate(true);
    	barProgressDialog.setCancelable(true);
    	barProgressDialog.setOnCancelListener(this);
    	barProgressDialog.show();

		myUpld = new RaceDatabase.upload(m_handler,this);
		myUpld.execute(iTrackCode);
    }

    private void downloadTracks() 
    {
    	barProgressDialog = new ProgressDialog(this);

    	barProgressDialog.setMessage("Downloading Tracks...");
    	barProgressDialog.setIndeterminate(true);
    	barProgressDialog.setCancelable(true);
    	barProgressDialog.setOnCancelListener(this);
    	barProgressDialog.show();

		myDnld = new RaceDatabase.download(m_handler,this);
    	myDnld.execute();
    }
	@Override
	public boolean handleMessage(Message msg) {
		if(msg.what == MSG_NEW_IMAGE)
		{
			ListView list = (ListView)findViewById(android.R.id.list);
			list.invalidateViews();

			return true;
		}
		if(msg.what == MSG_UPLOAD_COMPLETE)
		{
			myUpld.cancel(true);
			myUpld = null;
			if( barProgressDialog != null)
				barProgressDialog.dismiss();
			
			// Sadly, I need to exit the activity when we upload.  Something about launching Gmail 
			// on my phone will often cause this activity to crash.  Best to pre-emptively exit
			finish();
		}
		if(msg.what == MSG_DOWNLOAD_COMPLETE)
		{
			myDnld.cancel(true);
			myDnld = null;
			if( barProgressDialog != null)
				barProgressDialog.dismiss();

			switch( msg.arg1 ) {
			case -1:
				Toast.makeText(this, "Download failed!  Bad HTTP response. Check your internet connection.", Toast.LENGTH_LONG).show();
				break;
			case -2:
				WifiManager pWifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
				if( !pWifi.isWifiEnabled() )
					Toast.makeText(this, "Wifi is not enabled.", Toast.LENGTH_LONG).show();
				else {
					final boolean bConnected = (pWifi.getConnectionInfo().getSSID() == null);
					if( !bConnected )
						Toast.makeText(this, "Download failed! No wifi connection", Toast.LENGTH_LONG).show();
					else
						Toast.makeText(this, "Download failed! Can't access internet", Toast.LENGTH_LONG).show();
				}
				break;
			default:
				DoUIInit();
			}
			return true;
		}

		return false;
	}
	@Override
	public void onCancel(DialogInterface dialog) {
		if( myDnld != null )
			myDnld.cancel(true);
		if( myUpld != null )
			myUpld.cancel(true);
	}
	


}
