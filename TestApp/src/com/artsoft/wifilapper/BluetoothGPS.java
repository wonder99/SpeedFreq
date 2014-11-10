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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

public class BluetoothGPS
{
	public static final String NO_VTG_FOUND = "com.artsoft.BluetoothGPS.NoVTG";
	
	DeviceRecvThread m_thd;
	boolean bBtInsecure = false;
	
	public BluetoothGPS(String strDeviceName, LocationListener listener, boolean bBtInsecure)
	{
		this.bBtInsecure = bBtInsecure;
		BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
		if(ba.isEnabled())
		{
			Set<BluetoothDevice> lstDevices = ba.getBondedDevices();
			Iterator<BluetoothDevice> i = lstDevices.iterator();
			while(i.hasNext())
			{
				BluetoothDevice bd = i.next();
				if(strDeviceName.equals(bd.getName()))
				{
					// found our device
					m_thd = new DeviceRecvThread(bd, listener, bBtInsecure);
					m_thd.start();
					break;
				}
			}
		}
	}
	public boolean IsValid()
	{
		return m_thd != null;
	}
	public void Shutdown()
	{
		if(m_thd != null)
		{
			m_thd.Shutdown();
			m_thd = null;
		}
	}
	
	private static class DeviceRecvThread extends Thread implements Runnable, Handler.Callback
	{
		BluetoothDevice m_dev;
		LocationListener m_listener;
		boolean m_shutdown;
		Handler m_handler;
		Vector<Location> m_lstLocs; // must be accessed inside DeviceRecvThread's lock
		final int MSG_SENDLOCS = 101;
		final int MSG_LOSTGPS = 102;
		final int MSG_NOGPSDEVICE = 103;
		final int MSG_NOVTG = 104;
		
		// whether we're in "no VTG mode".  Since we can't assume that all our users will figure out the right thing to do wrt qstarz setup,
		// we should handle the case where it is missing
		boolean fNoVTGMode = false; 
		long tmLastVTGSeen = 0; // when we last saw a VTG.  Starts equal to the current time (since maybe one flew by just before the thread started)
		
		double dLastLat = 361;
		double dLastLong = 361;
		long lLastTime = -1;
		float dLastSpeed = 0;
		boolean bBtInsecure = false;
		
		public DeviceRecvThread(BluetoothDevice dev, LocationListener listener, boolean bBtInsecure)
		{
			m_lstLocs = new Vector<Location>();
			this.bBtInsecure = bBtInsecure;
			m_dev = dev;
			m_listener = listener;
			m_handler = new Handler(this);
		}
		public synchronized void Shutdown()
		{
			m_shutdown = true;
		}
		public static boolean ValidateNMEA(String nmea)
		{
			nmea = nmea.replace("\r\n", "");
			
			if(nmea.charAt(0) == '$')
			{
				final int ixAst = nmea.lastIndexOf("*");
				if(ixAst >= 0 && ixAst < nmea.length())
				{	
					final int ixLen = Math.min(ixAst+3, nmea.length());
					String strHex = nmea.substring(ixAst+1,ixLen);
					if(strHex.length() >= 1 && strHex.length() <= 2)
					{
						try
						{
						int iValue = Integer.parseInt(strHex, 16);
						byte bXORed = 0;
						for(int x = 1;x < ixAst; x++)
						{
							byte bValue = (byte)nmea.charAt(x);
							bXORed ^= bValue;
						}
						if(bXORed == iValue)
						{
							return true;
						}
						}
						catch(NumberFormatException e)
						{
							// just fall through and we'll return false below...
						}
					}
				}
			}
			return false;
		}

		private String ParseAndSendNMEA(String strNMEA)
		{
			final String strMatch = new String("$GPRMC");
			String strLastLeftover = strNMEA.substring(strNMEA.length()-Math.min(strNMEA.length(),strMatch.length()));
			int ixCur = strNMEA.indexOf(strMatch);
			while(ixCur != -1)
			{
				int ixNext = strNMEA.indexOf("$", ixCur+1);
				if(ixNext == -1)
				{
					strLastLeftover = strNMEA.substring(ixCur,strNMEA.length());
					break;
				}
				strLastLeftover = "";
				String strRMCCommand = strNMEA.substring(ixCur,ixNext);
				String strRMCBits[] = strRMCCommand.split(",");

				if(strRMCBits.length >= 6 && ValidateNMEA(strRMCCommand))
				{
					/*
					1) Time (UTC)
 					2) Status, V = Navigation receiver warning
 					3) Latitude
  					4) N or S
  					5) Longitude
  					6) E or W
 					7) Speed over ground, knots
  					8) Track made good, degrees true
 					9) Date, ddmmyy
 					10) Magnetic Variation, degrees
 					11) E or W
 					12) Checksum
					 */

					String strUTC = strRMCBits[1];
					String strLat = strRMCBits[3];
					String strNS = strRMCBits[4];
					String strLong = strRMCBits[5];
					String strEW = strRMCBits[6];
					String strSpeed = strRMCBits[7];

					Log.d("bt",strUTC+","+strLat+","+strNS+","+strLong+","+strEW+","+strSpeed);

					if(strUTC.length() > 0 && strLat.length() > 0 && strLong.length() > 0 && strNS.length() >= 1 && strEW.length() >= 1)
					{
						try
						{
							int iLatBase = Integer.parseInt(strLat.substring(0,2));
							int iLatFraction = Integer.parseInt(strLat.substring(2,4));
							double dLatFinal = Double.parseDouble(strLat.substring(4,strLat.length()));

							int iLongBase = Integer.parseInt(strLong.substring(0,3));
							int iLongFraction = Integer.parseInt(strLong.substring(3,5));
							double dLongFinal = Double.parseDouble(strLong.substring(5,strLong.length()));

							double dLat = iLatBase + ((double)iLatFraction+dLatFinal)/60.0;
							double dLong = iLongBase + ((double)iLongFraction+dLongFinal)/60.0;

							if(strNS.charAt(0) == 'S') dLat = -dLat;
							if(strEW.charAt(0) == 'W') dLong = -dLong;

							String strMinutes = strUTC.substring(2,4);
							String strSeconds = strUTC.substring(4,strUTC.length());
							int cMinutes = Integer.parseInt(strMinutes);
							double cSeconds = Double.parseDouble(strSeconds);
							int cMs = (int)((cSeconds - (int)cSeconds) * 1000);

							Calendar cal = Calendar.getInstance();
							cal.setTimeInMillis(System.currentTimeMillis());
							cal.set(Calendar.MINUTE, cMinutes);
							cal.set(Calendar.SECOND, (int)cSeconds);
							cal.set(Calendar.MILLISECOND, cMs);

							long lTime = cal.getTimeInMillis();

							float dSpeed = 0;
							if(fNoVTGMode)
							{
								// we're not getting VTG data, so let's get speed data from differences between lat/long coords
								float flDistance[] = new float[1];
								if(dLastLat <= 360 && dLastLong <= 360 && lLastTime > 0)
								{
									Location.distanceBetween(dLastLat, dLastLong, dLat, dLong, flDistance);
									float dTimeGap = (lTime - lLastTime) / 1000.0f;
									dSpeed = 0.3f*((flDistance[0] / dTimeGap) * 3.6f) + 0.7f * dLastSpeed;
									if(dSpeed > 200 || dSpeed < 0 )
									{
										// for whatever reason our speed is messed up.  Don't even bother reporting this.  720km/h should be plenty fast for our users
										break;
									}
									if(dTimeGap < 0 || flDistance[0] < 0) 
									{
										// Somehow, our calculated speed is negative.  Don't even bother reporting this.
										break; // set breakpoint, to see if it triggers.
									}
								}
								else
								{
									// we don't have a last latitude or longitude, so give up now
									lLastTime = lTime;
									dLastLat = dLat;
									dLastLong = dLong;
									break;
								}
							}
							else
							{
								dSpeed = (float)Double.parseDouble(strSpeed);
							}
							lLastTime = lTime;
							dLastLat = dLat;
							dLastLong = dLong;
							dLastSpeed = dSpeed;

							Location l = new Location("ArtBT");
							l.setLatitude(dLat);
							l.setLongitude(dLong);
							l.setTime(lTime);
							l.setSpeed(dSpeed/3.6f);

							synchronized(this)
							{
								m_lstLocs.add(l);
								m_handler.sendEmptyMessage(MSG_SENDLOCS);
							}
						}
						catch(Exception e)
						{
							// if it fails to parse the lat long, game over
							break;
						}
					}

					ixCur = strNMEA.indexOf(strMatch,ixCur+1);
				}
				else
				{
					break;
				}

				strLastLeftover = strNMEA.substring(strNMEA.length()-Math.min(strNMEA.length(),strMatch.length()));
			}
			return strLastLeftover;
		}

		@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
		public void run()
		{
			Thread.currentThread().setName("BT GPS thread");
			byte rgBuffer[] = new byte[2000];
			InputStream in = null;
			OutputStream out = null;
			BluetoothSocket bs = null;
			boolean fDeviceGood = false;
			while(!m_shutdown)
			{
				UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
							
				in = null;
				out = null;
				bs = null;
				fDeviceGood = false;
				
				// This first try is quite successful on most phones I tried
				try
				{
					Method m = m_dev.getClass().getMethod("createRfcommSocket", new Class[] {int.class});
			        bs = (BluetoothSocket) m.invoke(m_dev, 1);
					bs.connect();
					fDeviceGood = true; // if we got this far without an exception, the device is good
				}
				catch(IOException e){Log.w("bt", "Failed hidden secure attempt");} 
				catch (IllegalArgumentException e) { e.printStackTrace();} 
				catch (NoSuchMethodException e) {e.printStackTrace();} 
				catch (IllegalAccessException e) { e.printStackTrace();} 
				catch (InvocationTargetException e) {	e.printStackTrace();}
				
				// The secure connection should only be tried on devices that don't want insecure
				if (!fDeviceGood && !bBtInsecure )
				{
					try {
						bs.close();
						Thread.sleep(250);
						bs = m_dev.createRfcommSocketToServiceRecord(uuid);
						bs.connect();
						fDeviceGood = true; // if we got this far without an exception, the device is good
					} 
					catch (IOException e) {Log.w("bt", "Failed secure attempt");} 
					catch (InterruptedException e) {}
				}

				// We can try insecure connections on android versions >=10
				if (!fDeviceGood && bBtInsecure && Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1)
				{
					try
					{
						bs.close();
						Thread.sleep(250);
						bs = m_dev.createInsecureRfcommSocketToServiceRecord(uuid);
						bs.connect();
						fDeviceGood = true; // if we got this far without an exception, the device is good
					}
				catch (IOException e){Log.w("bt", "Failed insecure attempt");} 
				catch (InterruptedException e) {} 
				}

				if( fDeviceGood )
				{
					try {
						in = bs.getInputStream();
						out = bs.getOutputStream();
					} catch (IOException e) {Log.w("bt", "Failed creating input/output streams");}

					Log.w("bt", "Success");
				}
				else
					m_handler.sendEmptyMessage(MSG_NOGPSDEVICE);


				String strLastLeftover = "";
				int cbRead = 0;
				String strValid = "";
				String strToParse = "";
				while(fDeviceGood && !m_shutdown)
				{
					try 
					{
						cbRead = in.read(rgBuffer,0,rgBuffer.length);
					} 
					catch (IOException e) 
					{
						fDeviceGood = false; // we may need to re-acquire the BT device...
						m_handler.sendEmptyMessage(MSG_LOSTGPS);
						break;
					}
					if(cbRead > 0)
					{
						// Append the current buffer to the leftovers, and reparse
						strValid = new String(rgBuffer);
						strValid = strValid.substring(0, cbRead);
						strToParse = strLastLeftover + strValid;
						strLastLeftover = ParseAndSendNMEA(strToParse);
					}
				}
				if(in != null)
				{
					try{in.close(); } catch(IOException e2) {}
				}
				if(out != null)
				{
					try{out.close(); } catch(IOException e2) {}
				}
				if(bs != null)
				{
					try{bs.close(); } catch(IOException e2) {}
				}
				
				try{Thread.sleep(250);} catch(Exception e) {} // give the lost device some time to re-acquire
			}
		}
		private boolean fLostGPS = true;
		@Override
		public boolean handleMessage(Message msg) 
		{
			if(msg.what == MSG_SENDLOCS)
			{
				synchronized(this)
				{
					if(fLostGPS)
					{
						m_listener.onStatusChanged(LocationManager.GPS_PROVIDER, LocationProvider.AVAILABLE, null);
						fLostGPS = false;
					}
					for(int x = 0;x < m_lstLocs.size(); x++)
					{
						m_listener.onLocationChanged(m_lstLocs.get(x));
					}
					m_lstLocs.clear();
				}
			}
			else if(msg.what == MSG_LOSTGPS)
			{
				synchronized(this)
				{
					m_listener.onStatusChanged(LocationManager.GPS_PROVIDER, LocationProvider.TEMPORARILY_UNAVAILABLE, null);
					fLostGPS = true;
				}
			}
			else if(msg.what == MSG_NOGPSDEVICE)
			{
				synchronized(this)
				{
					m_listener.onStatusChanged(LocationManager.GPS_PROVIDER, LocationProvider.OUT_OF_SERVICE, null);
					fLostGPS = true;
				}
			}
			else if(msg.what == MSG_NOVTG)
			{
				synchronized(this)
				{
					m_listener.onStatusChanged(BluetoothGPS.NO_VTG_FOUND, 0, null);
				}
			}
			return false;
		}
	}
}
