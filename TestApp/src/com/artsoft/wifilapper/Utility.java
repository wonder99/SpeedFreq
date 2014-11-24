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

import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Surface;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;

public class Utility 
{
	static final int MSG_PICKED_DB = 120;
	public static int ParseInt(String str, int iDefault)
	{
		int iRet = 0;
		try
		{
			iRet = Integer.parseInt(str);
		}
		catch(NumberFormatException e)
		{
			iRet = iDefault;
		}
		return iRet;
	}

	// Prevent screen rotation.  Handles all known phone types, and their idiosyncrasies.
	// Not yet tested on API8, which doesn't support a couple of the methods we want to use
	public static void lockOrientation(Activity act) {
		int rotation = act.getWindowManager().getDefaultDisplay().getRotation();
		Utility.lockOrientation(act, rotation, Surface.ROTATION_270);
		// Ensure that the rotation hasn't changed
		if (act.getWindowManager().getDefaultDisplay().getRotation() != rotation) {
			Utility.lockOrientation(act, rotation, Surface.ROTATION_90);
		}
	}
	
	private static void lockOrientation(Activity act, int originalRotation, int naturalOppositeRotation) {
	    int orientation = act.getResources().getConfiguration().orientation;
	    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
	        // Are we reverse?
	        if (originalRotation == Surface.ROTATION_0 || originalRotation == naturalOppositeRotation) {
	            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
	        } else {
	            setReversePortrait(act);
	        }
	    } else {
	        // Are we reverse?
	        if (originalRotation == Surface.ROTATION_0 || originalRotation == naturalOppositeRotation) {
	            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
	        } else {
	            setReverseLandscape(act);
	        }
	    }
	}

	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	private static void setReversePortrait(Activity act) {
	    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
	        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
	    } else {
	        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
	    }
	}

	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	private static void setReverseLandscape(Activity act) {
	    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
	        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
	    } else {
	        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
	    }
	}

	// true -> a change was made
	// false -> no changes were made
	public static boolean ConnectToSSID(String strSSID, WifiManager pWifi)
	{
		// Note: since the SSID spinner is disabled if wifi is disabled, we know there will be non-null values for pInfo, lstNetworks.
		WifiInfo pInfo = pWifi.getConnectionInfo();
		if(pInfo != null && pWifi.isWifiEnabled())	
		{
			// Disconnect if on the wrong network
			if( pInfo != null && pInfo.getSSID()!= null && !pInfo.getSSID().replace("\"", "").equalsIgnoreCase(strSSID) )
				pWifi.disconnect();
			// check if not connected, or connected to the wrong network
			List<WifiConfiguration> lstNetworks = pWifi.getConfiguredNetworks();
			for(int x = 0;x < lstNetworks.size(); x++)
			{
				WifiConfiguration pNet = lstNetworks.get(x);
				if(pNet.SSID != null && pNet.SSID.replace("\"","").equalsIgnoreCase(strSSID))
				{
					pWifi.enableNetwork(pNet.networkId, true);
					return true;
				}
			}
		}
		return false;
	}
	public static void SetListViewHeightBasedOnChildren(ListView listView) 
	{
        ListAdapter listAdapter = listView.getAdapter();
        if (listAdapter == null) 
        {
            // pre-condition
            return;
        }

        int totalHeight = 0;
        int desiredWidth = MeasureSpec.makeMeasureSpec(listView.getWidth(), MeasureSpec.AT_MOST);
        for (int i = 0; i < listAdapter.getCount(); i++) 
        {
            View listItem = listAdapter.getView(i, null, listView);
            listItem.measure(desiredWidth, MeasureSpec.UNSPECIFIED);
            totalHeight += listItem.getMeasuredHeight();
        }

        ViewGroup.LayoutParams params = listView.getLayoutParams();
        params.height = totalHeight + (listView.getDividerHeight() * (listAdapter.getCount() - 1));
        listView.setLayoutParams(params);
        listView.requestLayout();
    }
	
	enum BOXJUSTIFY {LEFT_TOP,CENTER_TOP,RIGHT_TOP,LEFT_CENTER,CENTER_CENTER,RIGHT_CENTER,LEFT_BOTTOM,CENTER_BOTTOM,RIGHT_BOTTOM};
	public static Rect Justify(Rect rcToAlign, Rect rcTarget, BOXJUSTIFY iJustify)
	{
		int iDx = rcTarget.width() - rcToAlign.width() ;
		int iDy = rcTarget.height() - rcToAlign.height() ;
		switch( iJustify ) {
		case LEFT_TOP: 
		case LEFT_CENTER:
		case LEFT_BOTTOM:
			iDx = 0; break;			
		case CENTER_TOP:
		case CENTER_CENTER:
		case CENTER_BOTTOM:
			iDx = Math.round(iDx/2);
		default: // do nothigh for right justify 
		}
		switch( iJustify ) {
		case LEFT_TOP: 
		case CENTER_TOP:
		case RIGHT_TOP:
			iDy = 0; break;			// top justify
		case LEFT_CENTER: 
		case CENTER_CENTER:
		case RIGHT_CENTER:
			iDy = Math.round(iDy/2);// center justify
		default:	// do nothing for bottom justify 
		}

		return new Rect(rcTarget.left+iDx,rcTarget.top+iDy,rcTarget.left+iDx+rcToAlign.width(),rcTarget.top+iDy+rcToAlign.height());
	}
	
	public static Rect GetNeededFontSize(String str, Paint p, Rect rcScreen)
	{
		final int cxPermitted = rcScreen.width();
		final int cyPermitted = rcScreen.height();
		float fFontHigh = 700;
		float fFontLow = 10;
		boolean fTooBig = true;

		Rect rcBounds = new Rect();
		while(true)
		{
			if(fFontHigh - fFontLow <= 1 && !fTooBig)
			{
				// we've found the answer.  Return the smaller box
				return rcBounds;
			}
			else
			{
				float fFontCheck = (fFontHigh+fFontLow)/2;
				p.setTextSize(fFontCheck);
				p.getTextBounds(str, 0, str.length(), rcBounds);
				final int cxFont = rcBounds.width();
				final int cyFont = rcBounds.height();
				fTooBig = cxFont > cxPermitted || cyFont > cyPermitted;
				if(fTooBig)
				{
					fFontHigh = fFontCheck;
				}
				else
				{
					fFontLow = fFontCheck;
				}
			}
		}
	}
	public static String FormatSeconds(float seconds)
	{
		final long ms= (int)(seconds*1000);
		final long tenthsLeftOver = (ms/100) % 10;
		final long secondsLeftOver = (ms / 1000) % 60;
		final long minutes = ms/60000;
		
		String strSeconds = (secondsLeftOver<10 && minutes > 0) ? ("0" + secondsLeftOver) : ("" + secondsLeftOver);
		if(minutes > 0)
		{
			return minutes + ":" + strSeconds + "." + tenthsLeftOver;
		}
		else
		{
			return strSeconds + "." + tenthsLeftOver;
		}
	}
	public static void DrawFontInBox(Canvas c, final String str, Paint p, final Rect rcScreen)
	{
		DrawFontInBox(c, str, p, rcScreen, true);
	}
	public static String GetDateStringFromUnixTime(long timeInMilliseconds)
	{
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(timeInMilliseconds);
		return (c.get(Calendar.MONTH)+1 + "/" + c.get(Calendar.DATE) + "/" + c.get(Calendar.YEAR));
	}
	public static String GetReadableTime(long timeInMilliseconds)
	{
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(timeInMilliseconds);
		final int hours = c.get(Calendar.HOUR);
		final int minutes = c.get(Calendar.MINUTE);
		String strMinutes = minutes < 10 ? "0" + minutes : "" + minutes;
		if(hours > 12)
		{
			return (hours-12) + ":" + strMinutes + "pm";
		}
		else
		{
			return hours + ":" + strMinutes + ((hours == 12) ? "pm" : "am");
		}
		
	}
	public static String FormatFloat(float fl, int digits)
	{
		NumberFormat num = NumberFormat.getInstance();
		num.setMaximumFractionDigits(digits);
		num.setMinimumFractionDigits(digits);
		return num.format(fl);
	}
	enum TEXTJUSTIFY {LEFT,CENTER,RIGHT};
	public static void DrawFontInBoxFinal(Canvas c, final String str, float flFontSize, Paint p, Rect rcBounds, TEXTJUSTIFY iJustify)
	{
		p.setTextSize(flFontSize);
		
		switch ( iJustify ) {
		case LEFT: 
			p.setTextAlign(Align.LEFT);
			c.drawText(str, rcBounds.left,  rcBounds.bottom, p);
			break;
		case CENTER:
			p.setTextAlign(Align.CENTER);
			c.drawText(str, rcBounds.left + rcBounds.width()/2,  rcBounds.bottom, p);
			break;
		case RIGHT:
			p.setTextAlign(Align.RIGHT);
			c.drawText(str, rcBounds.right,  rcBounds.bottom, p);
			break;
		default:
			Log.e("DrawFontInBoxFinal","bad H justification");
		}
		p.setTextAlign(Align.LEFT);
	}
	
	public static void DrawFontInBoxFinal(Canvas c, final String str, float flFontSize, Paint p, final Rect rcScreen, boolean fLeftJustify, boolean fRightJustify, boolean fTopJustify)
	{
		// we've found the answer
		p.setTextSize(Math.round(flFontSize));
		
		RectF rcBounds = new RectF();
		//p.getTextBounds(str, 0, str.length(), rcBounds);
		
		rcBounds = new RectF(rcScreen);
		// measure text width
		rcBounds.right = p.measureText(str);
		// measure text height
		rcBounds.bottom =  p.getFontSpacing();//-p.descent();

		if( fLeftJustify )
			rcBounds.left = rcScreen.left;
		else if ( fRightJustify )
			rcBounds.left = rcScreen.right - rcBounds.right;
		else
			rcBounds.left += (rcScreen.width() - rcBounds.right) / 2.0f;
		
		if( fTopJustify )
			rcBounds.top = rcScreen.top;
		else
			rcBounds.top += (rcScreen.height() - rcBounds.bottom) / 2.0f;

		c.drawText(str, rcBounds.left, rcBounds.top-p.ascent(), p);
	}

	public static void DrawFontInBox(Canvas c, final String str, Paint p, final Rect rcScreen, boolean fActuallyDraw)
	{
		final int cxPermitted = rcScreen.width();//right - rcScreen.left;
		final int cyPermitted = rcScreen.height();//bottom - rcScreen.top;
		float fFontHigh = 522; // this will take 10 loops, but will support tablets 
		float fFontLow = 10;  
		
		while(true)
		{
			if(fFontHigh - fFontLow < .5)
			{
				// we've found the answer
				if(fActuallyDraw)
				{
					DrawFontInBoxFinal(c, str, Math.round(fFontLow), p, rcScreen, false,false,false);
				}
				else
				{
					p.setTextSize(Math.round(fFontLow));
				}
				return;
			}
			else
			{
				float fFontCheck = (fFontHigh+fFontLow)/2;
				p.setTextSize(fFontCheck);
				final float cxFont = p.measureText(str);
				Rect rTest = new Rect(rcScreen);
				p.getTextBounds(new String("0123456789"), 0, 10, rTest);
				final float cyFont = p.getFontSpacing()-p.descent();//rTest.height();
				final boolean fTooBig = cxFont > cxPermitted || cyFont > cyPermitted;
				if(fTooBig)
				{
					fFontHigh = fFontCheck;
				}
				else
				{
					fFontLow = fFontCheck;
				}
			}
		}
	}

	public static void oldDrawFontInBox(Canvas c, final String str, Paint p, final Rect rcScreen, boolean fActuallyDraw)
	{
		final int cxPermitted = rcScreen.right - rcScreen.left;
		final int cyPermitted = rcScreen.bottom - rcScreen.top;
		int iFontHigh = 522; // this will take 10 loops, but will support tablets 
		int iFontLow = 10;  
		
		Rect rcBounds = new Rect();
		while(true)
		{
			if(iFontHigh - iFontLow <= 1)
			{
				// we've found the answer
				if(fActuallyDraw)
				{
					DrawFontInBoxFinal(c, str, iFontLow, p, rcScreen, false,false,false);
				}
				else
				{
					p.setTextSize(iFontLow);
				}
				return;
			}
			else
			{
				int iFontCheck = (iFontHigh+iFontLow)/2;
				p.setTextSize(iFontCheck);
				p.getTextBounds(str, 0, str.length(), rcBounds);
				final int cxFont = rcBounds.right - rcBounds.left;
				final int cyFont = rcBounds.bottom - rcBounds.top;
				final boolean fTooBig = cxFont > cxPermitted || cyFont > cyPermitted;
				if(fTooBig)
				{
					iFontHigh = iFontCheck;
				}
				else
				{
					iFontLow = iFontCheck;
				}
			}
		}
	}
	public static String GetImagePathFromURI(Activity activity, Uri uri)
	{
	    String res = null;
	    String[] proj = { MediaStore.Images.Media.DATA };
	    Cursor cursor = activity.getContentResolver().query(uri, proj, null, null, null);
	    if(cursor.moveToFirst()){;
	       int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
	       res = cursor.getString(column_index);
	    }
	    cursor.close();
	    return res;
	}
	public interface MultiStateObject
	{
		public enum STATE {ON, OFF, TROUBLE_GOOD, TROUBLE_BAD};

		public static class StateData
		{
			public StateData(STATE eState, String str) {this.eState = eState; strDesc = str;}
			
			@Override
			public int hashCode()
			{
				return eState.hashCode() ^ (strDesc != null ? strDesc.hashCode() : 0);
			}
			
			public STATE eState;
			public String strDesc;
		}
		
		public abstract void SetState(Class<?> c, STATE eState, String strData);
		public abstract StateData GetState(Class<?> c);
	}

	public static String[] FindAllStorage(Context cxt)
	{
		List<String> strBuf = new ArrayList<String>();
		final String LOGTAG = "Storage";

		strBuf.add(cxt.getFilesDir().toString());
		final String state = Environment.getExternalStorageState();
		if ( Environment.MEDIA_MOUNTED.equals(state) ) {  // we can read the External Storage...           
		    //Retrieve the primary External Storage:
		    final File primaryExternalStorage = Environment.getExternalStorageDirectory();

		    //Retrieve the External Storages root directory:
		    final String externalStorageRootDir;
		    if ( (externalStorageRootDir = primaryExternalStorage.getParent()) == null ) {  // no parent...
		    	Log.d(LOGTAG, "External Storage: " + primaryExternalStorage + "\n");
		    	strBuf.add(externalStorageRootDir);
		    }
		    else {
		    	final File externalStorageRoot = new File( externalStorageRootDir );
		    	final File[] files = externalStorageRoot.listFiles();
//		    	final String[] fileNames = new String[files.length];
		    	StringBuffer fileNames;
		    	int i=0;
		    	for ( final File file : files ) {
		    		if ( file.isDirectory() && file.canWrite() && (file.listFiles().length > 0) ) {  // it is a real directory (not a USB drive)...
		    			Log.d(LOGTAG, "External Storage: " + file.getAbsolutePath() + "\n");
		    			strBuf.add(file.getAbsolutePath());
		    		}
		    	}
		    }
		}
		String[] ret = new String[strBuf.size()];
		ret = strBuf.toArray(ret);
		return ret;
	}
	
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
	public static void PickDBLocation(Context cxt, Handler handler, boolean bCancellable, boolean bWelcome)
	{
		final String[] fnames = FindAllStorage(cxt);
		String[] strPicker = new String[fnames.length];
		final float[] fSizes = new float[fnames.length];
		final Handler m_handler;
		m_handler = handler;
		
		final String strInternal = fnames[0];
		fnames[0] = new String("Internal");

		StatFs sfs;
		for( int i=0; i<fSizes.length;i++) {
			if( i == 0)
				sfs = new StatFs(strInternal);
			else
				sfs = new StatFs(fnames[i]);
			if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
				fSizes[i] = Math.round(sfs.getBlockSizeLong()  / 1024f * sfs.getAvailableBlocksLong() / 1024f);
			else {
				// OK to use deprecated API under this conditional
				fSizes[i] = Math.round(sfs.getAvailableBlocks() / 1024f * sfs.getBlockSize() / 1024f);
			}
			if( fSizes[i] > 1024f ) 
				strPicker[i] = new String(fnames[i] + " (" + String.valueOf(Math.round(fSizes[i]/102.4f)/10f) + "GB free)");
			else
				strPicker[i] = new String(fnames[i] + " (" + String.valueOf(Math.round(fSizes[i]*10)/10f) + "MB free)");
		}
		ListView modeList = new ListView(cxt);
		ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(cxt, R.layout.mysimplelistitem, R.id.tvListItem, strPicker);
		modeList.setAdapter(modeAdapter);

		AlertDialog.Builder ad = new AlertDialog.Builder(cxt)
		.setIcon(R.drawable.app_icon)
		.setCancelable(bCancellable)
		.setAdapter(modeAdapter, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				if( which > 0)
					RaceDatabase.SetLocation(fnames[which] + "/speedfreq/sfraces");
				else
					RaceDatabase.SetLocation(strInternal + "/sfraces");

				m_handler.sendEmptyMessage(MSG_PICKED_DB);
			}
		});
		if( bWelcome )
			ad.setTitle("Welcome to Speedfreq!  Choose a save location:");
		else
			ad.setTitle("Choose a save location:");

		ad.show();
	}


}
