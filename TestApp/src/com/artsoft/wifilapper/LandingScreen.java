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
import java.util.Calendar;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TabHost;
import android.widget.Toast;

public class LandingScreen extends android.app.TabActivity 
{
	private TabHost m_pTabHost;
	@Override
	protected void onCreate(Bundle savedInstanceState)
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
    	
	    setContentView(R.layout.landingscreen);
	    
		Intent intent;  // Reusable Intent for each tab
	    // Create an Intent to launch an Activity for the tab (to be reused)
	    
		m_pTabHost = (TabHost)findViewById(android.R.id.tabhost);

		Button txt = new Button(this);
		txt.setTextColor(Color.BLACK);
		txt.setTextSize(14);
		txt.setBackgroundResource(R.drawable.tabheader_blue);
		txt.setText("New Race");
	    intent = new Intent().setClass(this, LandingNewRace.class);
		m_pTabHost.addTab(m_pTabHost.newTabSpec("new").setIndicator(txt).setContent(intent));

		txt = new Button(this);
		txt.setTextColor(Color.BLACK);
		txt.setTextSize(14);
		txt.setBackgroundResource(R.drawable.tabheader_blue);
		txt.setText("Load Race");
	    intent = new Intent().setClass(this, LandingLoadRace.class);
		m_pTabHost.addTab(m_pTabHost.newTabSpec("load").setIndicator(txt).setContent(intent));

		txt = new Button(this);
		txt.setTextColor(Color.BLACK);
		txt.setTextSize(14);
		txt.setBackgroundResource(R.drawable.tabheader_blue);
		txt.setText("DB Backups");
	    intent = new Intent().setClass(this, LandingDBManage.class);
		m_pTabHost.addTab(m_pTabHost.newTabSpec("export").setIndicator(txt).setContent(intent));

		txt = new Button(this);
		txt.setTextColor(Color.BLACK);
		txt.setTextSize(14);
		txt.setBackgroundResource(R.drawable.tabheader_blue);
		txt.setText("Options");
	    intent = new Intent().setClass(this, LandingOptions.class);
		m_pTabHost.addTab(m_pTabHost.newTabSpec("options").setIndicator(txt).setContent(intent));
		
		m_pTabHost.setCurrentTab(0);
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
}
