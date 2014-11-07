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
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import com.artsoft.wifilapper.RaceDatabase.RaceData;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.TextView;
import android.widget.Toast;

public class SpeedFreq extends LandingRaceBase implements OnClickListener, DialogInterface.OnClickListener
{
	private Button m_btnStartRace;
	private TextView m_txtIP;
	ArrayList<RaceData> rdTracks;
	private int iSelectedTrack;	
    LocationManager locMan;
	private DialogInterface m_dlgAlert;
	private Intent m_startIntent = null;
	ImageView ivTrack;
	TextView tvTrackName;


	
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		if( BuildConfig.DEBUG ) {
			// Set up crash file directory
			String crashPath = Environment.getExternalStorageDirectory().getPath()+"/WifiLapperCrashes/";
			if(!(Thread.getDefaultUncaughtExceptionHandler() instanceof CustomExceptionHandler)) {
				Thread.setDefaultUncaughtExceptionHandler(new CustomExceptionHandler(crashPath));
			};
		}
		
		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);

		boolean fInternalDB = settings.getBoolean(Prefs.PREF_DBLOCATION_BOOL, Prefs.DEFAULT_DBLOCATION_BOOL);
		
		boolean fDBSuccess = SetupDatabase(fInternalDB);
		if(fDBSuccess)
		{
			// hooray!
		}
		else
		{
			// failed to create DB.  
			if(!fInternalDB)
			{
				// Are we in external mode?  Let's try internal
				fInternalDB = true;
				fDBSuccess = SetupDatabase(fInternalDB);
			}
			if(fDBSuccess)
			{
				// hooray!
				Toast.makeText(this, "WifiLapper was unable to use your external storage for the DB.  The DB is being stored on internal storage.  Is your SD card mounted?", Toast.LENGTH_LONG);
				settings.edit().putBoolean(Prefs.PREF_DBLOCATION_BOOL, fInternalDB).commit(); // changes the pref to use an internal DB
			}
			else
			{
				Toast.makeText(this, "WifiLapper was unable to load your database on either internal or external storages.  Is your phone out of space?", Toast.LENGTH_LONG).show();
				finish();
			}
		}

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		rdTracks = new ArrayList<RaceData>();

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
	    locMan = (LocationManager)getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
	    if(locMan != null && locMan.isProviderEnabled(LocationManager.GPS_PROVIDER))
	    {
	    	try
	    	{
	    		locMan.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100, 0.0f, (LocationListener)this);
	    	}
	    	catch(Exception e)
	    	{
	    		System.out.println("Failure: " + e.toString());
	    	}
	    }
		rdTracks = RaceDatabase.GetTrackData(RaceDatabase.Get());
    	SetupSettingsView();
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
		locMan = null;
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
    	m_btnStartRace.setTextSize(25*getResources().getDisplayMetrics().density);//TypedValue.COMPLEX_UNIT_PX,m_btnStartRace.getHeight());
    	
    	Button btnRaces = (Button)findViewById(R.id.btnRaces);
    	btnRaces.setOnClickListener(this);

    	Button btnTracks = (Button)findViewById(R.id.btnTracks);
    	btnTracks.setOnClickListener(this);

    	Button btnDbBackups = (Button)findViewById(R.id.btnDbBackups);
    	btnDbBackups.setOnClickListener(this);

    	Button btnOptions = (Button)findViewById(R.id.btnOptions);
    	btnOptions.setOnClickListener(this);
//    	Location loc =new Location(LocationManager.GPS_PROVIDER);
//    	loc.setLatitude(.1);
//    	loc.setLongitude(.1);
//    	onLocationChanged(loc);
//    	RaceDatabase.GetBitmapFromDatabase(RaceDatabase.Get(), "test");

//		RaceData rd = RaceDatabase.GetClosestTrack(rdTracks,-75,45);

		iSelectedTrack = settings.getInt(Prefs.PREF_TRACK_ID, Prefs.DEFAULT_TRACK_ID_INT);
		if( rdTracks != null && iSelectedTrack >= 0 ) 
		{
			int height=getWindowManager().getDefaultDisplay().getHeight();
			int width =getWindowManager().getDefaultDisplay().getWidth();
			int size;
			
			// Just force it, and let android scale it
			size=400;
			ivTrack.setImageBitmap(RaceDatabase.GetBitmapFromDatabase(RaceDatabase.Get(), iSelectedTrack,
					size,size));

			RaceData rd = RaceDatabase.GetTrackData(RaceDatabase.Get(), iSelectedTrack, 0);
			if( rd != null )
				tvTrackName.setText("Selected Track:\n" + rd.strRaceName);
			
		}
		else 
		{
			tvTrackName.setText("A new S/F will be set");
			iSelectedTrack = -1;
			ivTrack.setImageResource(R.drawable.iconbig);
		}
		ivTrack.setOnClickListener(this);
		tvTrackName.setOnClickListener(this);

		// disable network features if not enabled
		WifiManager pWifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
		if( !pWifi.isWifiEnabled() ) {
			btnAutoIP.setEnabled(false);
			tvSSID.setEnabled(false);
			m_txtIP.setEnabled(false);
			tvIPLabel.setEnabled(false);
		}
    }

	@Override
	public void onClick(View v) 
	{
		if(v.getId() == R.id.btnStartRace)
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
			String strBTGPS = settings.getString(Prefs.PREF_BTGPSNAME_STRING, Prefs.DEFAULT_GPS_STRING);
			String strBTOBD2 = settings.getString(Prefs.PREF_BTOBD2NAME_STRING, Prefs.DEFAULT_OBD2_STRING);
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
    		
    		LapAccumulator.LapAccumulatorParams lapParams = new LapAccumulator.LapAccumulatorParams();
    		lapParams.iCarNumber = settings.getInt(Prefs.PREF_CARNUMBER, Prefs.DEFAULT_CARNUMBER);
    		lapParams.iSecondaryCarNumber = (int)(Math.random() * 100000.0); 
    		lapParams.iFinishCount = iFinishCount;
    		
    		Intent i = ApiDemos.BuildStartIntent(fRequireWifi, fUseIOIO, rgAnalPins,rgPulsePins, iButtonPin, fUseP2P, iStartMode, flStartParam, iStopMode, flStopParam, lstSelectedPIDs, getApplicationContext(), strIP,strSSID, lapParams, strRaceName, strPrivacy, fAckSMS, fUseAccel, fUseAccelCorrection, iFilterType, flPitch, flRoll, flSensorOffset, fTestMode, bWifiScan, -1, -1, strBTGPS, strBTOBD2, strSpeedoStyle, eUnitSystem.toString());
    		if(fTestMode)
    		{
    			// they're about to start a run in test mode.  Test mode sucks for real users, so warn them
    			AlertDialog ad = new AlertDialog.Builder(this).create();
    			ad.setMessage("Test mode is currently selected.  GPS reception will be disabled.  Are you sure?");
    			ad.setButton(AlertDialog.BUTTON_POSITIVE,"Yes", this);
    			ad.setButton(AlertDialog.BUTTON_NEGATIVE,"No/Cancel", this);
    			m_startIntent = i;
    			m_dlgAlert = ad;
    			ad.show();
//    			
//                //Ask the user if they want to quit
//                AlertDialog ad = new AlertDialog.Builder(this)
//                .setIcon(android.R.drawable.ic_dialog_alert)
//                .setTitle(R.string.quit)
//                .setMessage(R.string.really_quit)
//                .setPositiveButton(R.string.yes, this)
//                .setNegativeButton(R.string.no, null).show();
//    			m_startIntent = i;
//    			m_dlgAlert = ad;

                
    		}
    		else
    		{
    			startActivity(i);
    		}
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
		else if(v.getId() == R.id.btnTracks)
		{
			startActivity(new Intent().setClass(this, LandingTracks.class));
//			iSelectedTrack = -1;
//			ivTrack.setImageResource(R.drawable.iconbig);
////			ivTrack.setScaleType(ScaleType.CENTER_INSIDE);
//
//			tvTrackName.setText("A new S/F will be set");
//			SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
//			settings.edit().putInt(Prefs.PREF_TRACK_ID, iSelectedTrack).commit();
		}
		else if(v.getId() == R.id.imTrack || v.getId() == R.id.tvTrackName)
		{
			startActivity(new Intent().setClass(this, LandingTracks.class));
		}
		else if(v.getId() == R.id.btnAutoIP)
		{
			ShowAutoIPActivity();
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

	private boolean SetupDatabase(boolean fInternal)
	{
		if(fInternal)
		{
			return RaceDatabase.CreateInternal(getApplicationContext(), getFilesDir().toString());
		}
		else
		{
			return RaceDatabase.CreateExternal(getApplicationContext());
		}
	}

	private static class CustomExceptionHandler implements UncaughtExceptionHandler 
	{
		private String localPath;
		private UncaughtExceptionHandler m_oldHandler;
		
		public CustomExceptionHandler(String localPath) 
		{
	        this.localPath = localPath;
	    	File fileTest = new File(localPath);
		    if ( !fileTest.isDirectory() )
		    	fileTest.mkdir();

	        this.m_oldHandler = Thread.getDefaultUncaughtExceptionHandler();
		}
		
		@Override
		public void uncaughtException(Thread t, Throwable e) 
		{
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

	        m_oldHandler.uncaughtException(t, e);
	    }

	    private void writeToFile(String stacktrace, String filename) {
	        try {
	            BufferedWriter bos = new BufferedWriter(new FileWriter(
	                    localPath + "/" + filename));
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
	
}
