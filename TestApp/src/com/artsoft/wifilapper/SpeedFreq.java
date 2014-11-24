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
import java.io.FileNotFoundException;
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

import com.artsoft.wifilapper.RaceDatabase.RaceData;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.os.Handler.Callback;
import android.os.Message;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class SpeedFreq extends LandingRaceBase implements Callback, OnClickListener, DialogInterface.OnClickListener
{
	private Button m_btnStartRace;
	private TextView m_txtIP;
	//	ArrayList<RaceData> rdTracks;
	private int iSelectedTrack;	
	private DialogInterface m_dlgAlert;
	private Intent m_startIntent = null;
	ImageView ivTrack;
	TextView tvTrackName;
	Context m_context;
	private BroadcastListener m_listener; // For wifi state changes

	Handler m_handler;

	boolean bGotDB;


	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		m_handler = new Handler(this);
		m_context = this;
		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);

		String strDBPath = settings.getString(Prefs.PREF_DBLOCATION_STRING,null);
		if( strDBPath == null )
			Utility.PickDBLocation(this,m_handler,false,true);
		else {
			RaceDatabase.SetLocation(strDBPath);
			RaceDatabase.CreateOnPath(getApplicationContext());
//			Toast.makeText(this, "Opened DB at " + RaceDatabase.getPath(), Toast.LENGTH_LONG).show();
		}
			
		if( BuildConfig.DEBUG ) {
			final String state = Environment.getExternalStorageState();
			if ( Environment.MEDIA_MOUNTED.equals(state) ) {  // we can read the External Storage...           
				//Retrieve the primary External Storage:
				final File primaryExternalStorage = Environment.getExternalStorageDirectory();
				final String strCrashPath = primaryExternalStorage.getPath() + "/speedfreq/crashlogs/";
				if(!(Thread.getDefaultUncaughtExceptionHandler() instanceof CustomExceptionHandler)) {
					Thread.setDefaultUncaughtExceptionHandler(new CustomExceptionHandler(strCrashPath));
				};
			}
		}

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

		View vNewRace = (View)View.inflate(this, R.layout.speedfreq, null);
		setContentView(vNewRace);		

		m_txtIP = (TextView)findViewById(R.id.txtIP);

		ivTrack = (ImageView) findViewById(R.id.imTrack);
		tvTrackName = (TextView) findViewById(R.id.tvTrackName);

	}

	@Override
	public void onResume()
	{
		super.onResume();
		getWindow().setSoftInputMode(
				WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

		SetupSettingsView();

		// Set up listener to watch Wifi state, enable/disable views
		m_listener = new BroadcastListener();
		IntentFilter wifiFilter = new IntentFilter();
		wifiFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
		this.registerReceiver(m_listener, wifiFilter);

		// Check the 'in progress' cookie
		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
		boolean bAutoRestart = settings.getBoolean(Prefs.PREF_AUTO_RESTART_BOOL, false);
		boolean bRaceInProgress = settings.getBoolean(Prefs.PREF_RACE_IN_PROGRESS, false);
		if( bAutoRestart && bRaceInProgress )
			StartRace(); // Restart the race, since shutdown was ungraceful
	}
	@Override
	public void onPause()
	{
		super.onPause();                      
		EditText edtRaceName = (EditText)findViewById(R.id.edtRaceName);

		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
		//final boolean fSSIDGood = (spnSSID.isEnabled() && spnSSID.getSelectedItem() != null);
		ApiDemos.SaveSharedPrefs(settings, 
				m_txtIP.getText().toString(), 
				null, // no SSID changes here 
				null, // no GPS changes here
				edtRaceName.getText().toString());

		this.unregisterReceiver(m_listener);

	}

	private void SetupSettingsView()
	{
		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);

		Button btnAutoIP = (Button)findViewById(R.id.btnAutoIP);
		btnAutoIP.setOnClickListener(this);

		String strSSID = settings.getString(Prefs.PREF_SSID_STRING, Prefs.DEFAULT_SSID_STRING);
		TextView tvSSID = (TextView)findViewById(R.id.txtSSID);
		tvSSID.setText("SSID: " + strSSID);

		TextView tvIPLabel = (TextView)findViewById(R.id.txtIPLabel);
		String strIP = settings.getString(Prefs.PREF_IP_STRING,Prefs.DEFAULT_IP_STRING);
		m_txtIP.setText(strIP);

		EditText edtRaceName = (EditText)findViewById(R.id.edtRaceName);
		String strRaceName = settings.getString(Prefs.PREF_RACENAME_STRING, Prefs.DEFAULT_RACENAME_STRING);
		edtRaceName.setText(strRaceName);

		m_btnStartRace = (Button)findViewById(R.id.btnStartRace);
		m_btnStartRace.setOnClickListener(this);
		m_btnStartRace.setText("Start Race");

		Button btnRaces = (Button)findViewById(R.id.btnRaces);
		btnRaces.setOnClickListener(this);

		Button btnTracks = (Button)findViewById(R.id.btnTracks);
		btnTracks.setOnClickListener(this);

		Button btnDbBackups = (Button)findViewById(R.id.btnDbBackups);
		btnDbBackups.setOnClickListener(this);

		Button btnOptions = (Button)findViewById(R.id.btnOptions);
		btnOptions.setOnClickListener(this);

		iSelectedTrack = settings.getInt(Prefs.PREF_TRACK_ID, Prefs.DEFAULT_TRACK_ID_INT);
		if( iSelectedTrack >= 0 )
		{
			int size;

			size=Math.round(Math.min(getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels) *.48f);
			ivTrack.setImageBitmap(RaceDatabase.GetBitmapFromDatabase(RaceDatabase.Get(), iSelectedTrack,
					size,size));
			ivTrack.setScaleType(ScaleType.CENTER);

			RaceData rd = RaceDatabase.GetTrackData(RaceDatabase.Get(), iSelectedTrack, 0);
			if( rd != null )
				tvTrackName.setText("Selected Track: " + rd.strRaceName);
		}
		else 
		{
			tvTrackName.setText("A new Start/Finish will be set");
			iSelectedTrack = -1;
			ivTrack.setImageResource(R.drawable.speedfreq);
			ivTrack.setScaleType(ScaleType.CENTER_INSIDE); // center_inside will only scale down
		}
		ivTrack.setOnClickListener(this);

		// For testing the crash-reporting feature
		tvTrackName.setOnClickListener(this);
	}

	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	public void StartRace() 
	{
		EditText edtRaceName = (EditText)findViewById(R.id.edtRaceName);
		String strRaceName = edtRaceName.getText().toString();

		Prefs.UNIT_SYSTEM eUnitSystem;

		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
		String strIP = settings.getString(Prefs.PREF_IP_STRING,Prefs.DEFAULT_IP_STRING);
		String strSSID = settings.getString(Prefs.PREF_SSID_STRING, Prefs.DEFAULT_SSID_STRING);
		String strSpeedoStyle = settings.getString(Prefs.PREF_SPEEDOSTYLE_STRING, Prefs.DEFAULT_SPEEDOSTYLE_STRING);
		boolean fTestMode = settings.getBoolean(Prefs.PREF_TESTMODE_BOOL, Prefs.DEFAULT_TESTMODE_BOOL);
		boolean bWifiScan = settings.getBoolean(Prefs.PREF_WIFI_SCAN_BOOL, Prefs.DEFAULT_WIFI_SCAN_BOOL);
		String strUnitSystem = settings.getString(Prefs.PREF_UNITS_STRING, Prefs.DEFAULT_UNITS_STRING.toString());

		Boolean strBTGPSEn = settings.getBoolean(Prefs.PREF_BTGPSENABLED_BOOL, false);
		String strBTGPS = strBTGPSEn ? settings.getString(Prefs.PREF_BTGPSNAME_STRING, Prefs.DEFAULT_GPS_STRING) : "";

		Boolean strBTOBD2En = settings.getBoolean(Prefs.PREF_BTOBD2ENABLED_BOOL, false);
		String strBTOBD2 = strBTOBD2En ? settings.getString(Prefs.PREF_BTOBD2NAME_STRING, Prefs.DEFAULT_OBD2_STRING) : "";

		boolean fUseIOIO = settings.getBoolean(Prefs.PREF_USEIOIO_BOOLEAN, Prefs.DEFAULT_USEIOIO_BOOLEAN);
		boolean fUseAccel = settings.getBoolean(Prefs.PREF_USEACCEL_BOOLEAN, Prefs.DEFAULT_USEACCEL);
		boolean fUseAccelCorrection = settings.getBoolean(Prefs.PREF_ACCEL_CORRECTION, Prefs.DEFAULT_ACCEL_CORRECTION);
		float flPitch = settings.getFloat(Prefs.PREF_ACCEL_CORRECTION_PITCH, Prefs.DEFAULT_ACCEL_CORRECTION_PITCH);
		float flRoll  = settings.getFloat(Prefs.PREF_ACCEL_CORRECTION_ROLL, Prefs.DEFAULT_ACCEL_CORRECTION_ROLL);
		float[] flSensorOffset = new float[3];
		flSensorOffset[1]  = settings.getFloat(Prefs.PREF_ACCEL_OFFSET_X, Prefs.DEFAULT_ACCEL_OFFSET_X);
		flSensorOffset[2]  = settings.getFloat(Prefs.PREF_ACCEL_OFFSET_Y, Prefs.DEFAULT_ACCEL_OFFSET_Y);
		flSensorOffset[0]  = settings.getFloat(Prefs.PREF_ACCEL_OFFSET_Z, Prefs.DEFAULT_ACCEL_OFFSET_Z);
		int iFilterType = settings.getInt(Prefs.PREF_ACCEL_FILTER, Prefs.DEFAULT_ACCEL_FILTER);
		boolean fAckSMS = settings.getBoolean(Prefs.PREF_ACKSMS_BOOLEAN, Prefs.DEFAULT_ACKSMS);
		String strPrivacy = settings.getString(Prefs.PREF_PRIVACYPREFIX_STRING, Prefs.DEFAULT_PRIVACYPREFIX);
		int iButtonPin = settings.getInt(Prefs.PREF_IOIOBUTTONPIN, Prefs.DEFAULT_IOIOBUTTONPIN);

		boolean fUseP2P = false;//rgMode.getCheckedRadioButtonId() == R.id.rbPointToPoint;
		final int iStartMode = settings.getInt(Prefs.PREF_P2P_STARTMODE, Prefs.DEFAULT_P2P_STARTMODE);
		final float flStartParam = settings.getFloat(Prefs.PREF_P2P_STARTPARAM, Prefs.DEFAULT_P2P_STARTPARAM);
		final int iStopMode = settings.getInt(Prefs.PREF_P2P_STOPMODE, Prefs.DEFAULT_P2P_STOPMODE);
		final float flStopParam = settings.getFloat(Prefs.PREF_P2P_STOPPARAM, Prefs.DEFAULT_P2P_STOPPARAM);
		final boolean fRequireWifi = settings.getBoolean(Prefs.PREF_REQUIRE_WIFI, Prefs.DEFAULT_REQUIRE_WIFI);

		final int iFinishCount = 1;//Utility.ParseInt(edtFinishCount.getText().toString(), 1);

		eUnitSystem = Prefs.UNIT_SYSTEM.valueOf(strUnitSystem);

		ApiDemos.SaveSharedPrefs(settings, null, null, null, strRaceName);


		List<Integer> lstSelectedPIDs = new ArrayList<Integer>();
		Prefs.LoadOBD2PIDs(settings, lstSelectedPIDs);

		IOIOManager.PinParams rgAnalPins[] = Prefs.LoadIOIOAnalPins(settings);
		IOIOManager.PinParams rgPulsePins[] = Prefs.LoadIOIOPulsePins(settings);

		LapAccumulator.LapAccumulatorParams lapParams = null;
		RaceData rd;
		if( iSelectedTrack >= 0 && !fTestMode ) {
			rd = RaceDatabase.GetTrackData(RaceDatabase.Get(), iSelectedTrack, 0);
			if( rd != null )
				lapParams = rd.lapParams;
		}
		if( lapParams == null )
			lapParams = new LapAccumulator.LapAccumulatorParams();

		lapParams.iCarNumber = settings.getInt(Prefs.PREF_CARNUMBER, Prefs.DEFAULT_CARNUMBER);
		lapParams.iSecondaryCarNumber = (int)(Math.random() * 100000.0); 
		lapParams.iFinishCount = iFinishCount;

		Intent i = ApiDemos.BuildStartIntent(fRequireWifi, fUseIOIO, rgAnalPins,rgPulsePins, iButtonPin, 
				fUseP2P, iStartMode, flStartParam, iStopMode, flStopParam, lstSelectedPIDs, 
				getApplicationContext(), strIP,strSSID, lapParams, strRaceName, strPrivacy, fAckSMS, 
				fUseAccel, fUseAccelCorrection, iFilterType, flPitch, flRoll, flSensorOffset, fTestMode, 
				bWifiScan, -1, -1, strBTGPS, strBTOBD2, strSpeedoStyle, eUnitSystem.toString());
		if(fTestMode)
		{
			// they're about to start a run in test mode.  Test mode sucks for real users, so warn them
			AlertDialog ad = new AlertDialog.Builder(new ContextThemeWrapper(this, android.R.style.Theme_Dialog)).create();
			ad.setMessage("Test mode is currently selected.  GPS reception will be disabled.  Are you sure?");
			ad.setButton(AlertDialog.BUTTON_POSITIVE,"Yes", this);
			ad.setButton(AlertDialog.BUTTON_NEGATIVE,"No/Cancel", this);
			m_startIntent = i;
			m_dlgAlert = ad;
			ad.show();
		}
		else
		{
			Editor edit = settings.edit();
			boolean bAutoRestart = settings.getBoolean(Prefs.PREF_AUTO_RESTART_BOOL,false);
			if( bAutoRestart ) {
				// Leave a cookie, so that this race can be restarted upon a crash
				edit = edit.putBoolean(Prefs.PREF_RACE_IN_PROGRESS, true);
			}
			else {
				// clear out the in-progress flag if not using it
				edit = edit.putBoolean(Prefs.PREF_RACE_IN_PROGRESS, false);
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD)
				edit.apply();
			else
				edit.commit();
			startActivity(i);
		}
	}

	@Override
	public void onClick(View v) 
	{
		if(v.getId() == R.id.btnStartRace)
		{
			StartRace();
		}
		else if(v.getId() == R.id.btnRaces)
		{
			startActivity(new Intent().setClass(this, LandingLoadRace.class));
		}
		else if(v.getId() == R.id.btnDbBackups)
		{
			startActivity(new Intent().setClass(this, LandingDBManage.class));
		}
		else if(v.getId() == R.id.btnOptions)
		{
			startActivity(new Intent().setClass(this, LandingOptions.class));
		}
		else if(v.getId() == R.id.imTrack || v.getId() == R.id.btnTracks)
		{
			startActivity(new Intent().setClass(this, LandingTracks.class));
		}
		else if(v.getId() == R.id.btnAutoIP)
		{
			ShowAutoIPActivity();
		}
		else if(v.getId() == R.id.tvTrackName)
		{
			// for testing crash reporting
			throw new AssertionError("Test Crash!");
		}
	}

	@Override
	public void onClick(DialogInterface dlg, int choice) 
	{
		if(dlg == m_dlgAlert && m_startIntent != null)
		{
			// they clicked our alert dialog
			if(choice == Dialog.BUTTON_POSITIVE)
			{
				// they're fine with test mode, so go ahead
				startActivity(m_startIntent);
			}
			else if(choice == Dialog.BUTTON_NEGATIVE)
			{
				// they have wisely chosen to avoid test mode
			}
			dlg = null;
			m_startIntent = null;
		}
	}


	private static class CustomExceptionHandler implements UncaughtExceptionHandler 
	{
		private String localPath;
		private UncaughtExceptionHandler m_oldHandler;

		public CustomExceptionHandler(String localPath) 
		{
			this.localPath = localPath;
			this.m_oldHandler = Thread.getDefaultUncaughtExceptionHandler();
		}

		@Override
		public void uncaughtException(Thread t, Throwable e) 
		{
			File fileTest = new File(localPath);

			if ( !fileTest.isDirectory() )
				fileTest.mkdirs();

			if( fileTest.canWrite() ) {
				Calendar cal = Calendar.getInstance();
				final Writer result = new StringWriter();
				final PrintWriter printWriter = new PrintWriter(result);
				e.printStackTrace(printWriter);
				String stacktrace = result.toString();
				printWriter.close();
				String filename = cal.get(Calendar.YEAR) + "." + String.valueOf(cal.get(Calendar.MONTH)+1) + "." + cal.get(Calendar.DAY_OF_MONTH) + 
						"." + cal.get(Calendar.HOUR_OF_DAY) + "." + cal.get(Calendar.MINUTE) + ".stacktrace.txt";

				if (localPath != null) {
					writeToFile(stacktrace, filename);
				}
			}
			m_oldHandler.uncaughtException(t, e);
		}
		private void writeToFile(String stacktrace, String filename) {
			try {
				BufferedWriter bos = new BufferedWriter(new FileWriter(
						localPath + filename));
				bos.write(stacktrace);
				bos.flush();
				bos.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	protected void SetIPString(String strIP) {
		m_txtIP.setText(strIP);
		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
		ApiDemos.SaveSharedPrefs(settings, 
				m_txtIP.getText().toString(), null,null,null);

	}

	// Live enabling/disabling of views, based on Wifi state
	private class BroadcastListener extends BroadcastReceiver
	{
		Button btnAutoIP = (Button)findViewById(R.id.btnAutoIP);
		TextView tvSSID = (TextView)findViewById(R.id.txtSSID);
		TextView tvIPLabel = (TextView)findViewById(R.id.txtIPLabel);
		WifiManager pWifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
		boolean bWifiOn;

		@Override
		public void onReceive(Context ctx, Intent intent)
		{
			if(intent.getAction().equals(WifiManager.WIFI_STATE_CHANGED_ACTION))
			{
				// disable network features if not enabled
				bWifiOn = pWifi.isWifiEnabled(); 
				btnAutoIP.setEnabled(bWifiOn);
				tvSSID.setEnabled(bWifiOn);
				m_txtIP.setEnabled(bWifiOn);
				tvIPLabel.setEnabled(bWifiOn);

			}
		}
	}

	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	@Override
	public boolean handleMessage(Message msg) {
		if( msg.what == Utility.MSG_PICKED_DB) {
			// location has been set already
			RaceDatabase.CreateOnPath(getApplicationContext());

			SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
			Editor edit = settings.edit();
			edit.putString(Prefs.PREF_DBLOCATION_STRING, RaceDatabase.getPath());
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD)
				edit.apply();
			else
				edit.commit();

//			Toast.makeText(this, "Created DB at " + RaceDatabase.getPath(), Toast.LENGTH_LONG).show();


			ListView modeList = new ListView(this);
			ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this, R.layout.mysimplelistitem, R.id.tvListItem, new String[] {"one","twp"});
			modeList.setAdapter(modeAdapter);

			final String strImportPath = RaceDatabase.GetExternalDir(m_context) + "/wifilapper/races";			
			final File fImportDB = new File(strImportPath);
			if( fImportDB.exists() && fImportDB.canRead() ) {
				final float fSize =Math.round(fImportDB.length()/102.4f/1024f)/10f;
				new AlertDialog.Builder(this)
				.setIcon(R.drawable.wfl_icon)
				.setTitle("Wifilapper Import")
				.setMessage("Do you want to import the " + String.valueOf(fSize) 
						+ "MB database from " + strImportPath )
						.setCancelable(true)
						.setNegativeButton("No", null)
						.setPositiveButton("Yes",  new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								fImportDB.length();
								if( fImportDB.canRead() ) {
									Toast.makeText(m_context, "Importing wifilapper database from "+strImportPath
											, Toast.LENGTH_LONG).show();
									FileInputStream in=null;
									FileOutputStream out=null;
									try {
										in = new FileInputStream(strImportPath);
										out = new FileOutputStream(RaceDatabase.getPath());
									}
									catch (FileNotFoundException e) {
										// OK, just creating it
									}
									if( in != null && out != null ) {
										try {
											// Copy the bits from instream to outstream
											byte[] buf = new byte[1024];
											int len;
											while ((len = in.read(buf)) > 0) {
												out.write(buf, 0, len);
											}
											in.close();
											out.close();
										} catch (IOException e) {
											e.printStackTrace();
										}
									}
								}
							}
						})
						.show();
			}
			return true;
		}
		else
			return false;
	}


}
