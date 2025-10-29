/*
 * Copyright 2015 Google Inc. All rights reserved.
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

package io.github.webbluetoothcg.bletestperipheral;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import io.github.webbluetoothcg.bletestperipheral.ServiceFragment.ServiceFragmentDelegate;

public class Peripheral extends Activity implements ServiceFragmentDelegate {

  private static final int REQUEST_ENABLE_BT = 1;
  private static final int REQUEST_ALL_PERMISSIONS = 2; // New constant for permission request
  private static final String TAG = Peripheral.class.getCanonicalName();
  private static final String CURRENT_FRAGMENT_TAG = "CURRENT_FRAGMENT";

  private static final UUID CHARACTERISTIC_USER_DESCRIPTION_UUID = UUID
          .fromString("00002901-0000-1000-8000-00805f9b34fb");
  private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID
          .fromString("00002902-0000-1000-8000-00805f9b34fb");

  private TextView mAdvStatus;
  private TextView mConnectionStatus;
  private ServiceFragment mCurrentServiceFragment;
  private BluetoothGattService mBluetoothGattService;
  private HashSet<BluetoothDevice> mBluetoothDevices;
  private BluetoothManager mBluetoothManager;
  private BluetoothAdapter mBluetoothAdapter;
  private AdvertiseData mAdvData;
  private AdvertiseData mAdvScanResponse;
  private AdvertiseSettings mAdvSettings;
  private BluetoothLeAdvertiser mAdvertiser;
  private final AdvertiseCallback mAdvCallback = new AdvertiseCallback() {
    @Override
    public void onStartFailure(int errorCode) {
      super.onStartFailure(errorCode);
      Log.e(TAG, "Not broadcasting: " + errorCode);
      int statusText;
      switch (errorCode) {
        case ADVERTISE_FAILED_ALREADY_STARTED:
          statusText = R.string.status_advertising;
          Log.w(TAG, "App was already advertising");
          break;
        case ADVERTISE_FAILED_DATA_TOO_LARGE:
          statusText = R.string.status_advDataTooLarge;
          break;
        case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
          statusText = R.string.status_advFeatureUnsupported;
          break;
        case ADVERTISE_FAILED_INTERNAL_ERROR:
          statusText = R.string.status_advInternalError;
          break;
        case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
          statusText = R.string.status_advTooManyAdvertisers;
          break;
        default:
          statusText = R.string.status_notAdvertising;
          Log.wtf(TAG, "Unhandled error: " + errorCode);
      }
      mAdvStatus.setText(statusText);
    }

    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
      super.onStartSuccess(settingsInEffect);
      Log.v(TAG, "Broadcasting");
      mAdvStatus.setText(R.string.status_advertising);
    }
  };

  private BluetoothGattServer mGattServer;
  private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
    @Override
    public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
      super.onConnectionStateChange(device, status, newState);
      if (status == BluetoothGatt.GATT_SUCCESS) {
        if (newState == BluetoothGatt.STATE_CONNECTED) {
          mBluetoothDevices.add(device);
          updateConnectedDevicesStatus();
          Log.v(TAG, "Connected to device: " + device.getAddress());
        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
          mBluetoothDevices.remove(device);
          updateConnectedDevicesStatus();
          Log.v(TAG, "Disconnected from device");
        }
      } else {
        mBluetoothDevices.remove(device);
        updateConnectedDevicesStatus();
        final String errorMessage = getString(R.string.status_errorWhenConnecting) + ": " + status;
        runOnUiThread(() -> Toast.makeText(Peripheral.this, errorMessage, Toast.LENGTH_LONG).show());
        Log.e(TAG, "Error when connecting: " + status);
      }
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattCharacteristic characteristic) {
      super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
      if (ActivityCompat.checkSelfPermission(Peripheral.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "Permission check failed in onCharacteristicReadRequest");
        return;
      }
      Log.d(TAG, "Device tried to read characteristic: " + characteristic.getUuid());
      Log.d(TAG, "Value: " + Arrays.toString(characteristic.getValue()));
      if (offset != 0) {
        mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
        return;
      }
      mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
              offset, characteristic.getValue());
    }

    @Override
    public void onNotificationSent(BluetoothDevice device, int status) {
      super.onNotificationSent(device, status);
      Log.v(TAG, "Notification sent. Status: " + status);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
      super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
              responseNeeded, offset, value);
      if (ActivityCompat.checkSelfPermission(Peripheral.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "Permission check failed in onCharacteristicWriteRequest");
        return;
      }
      Log.v(TAG, "Characteristic Write request: " + Arrays.toString(value));
      int status = mCurrentServiceFragment.writeCharacteristic(characteristic, offset, value);
      if (responseNeeded) {
        mGattServer.sendResponse(device, requestId, status, 0, null);
      }
    }

    @Override
    public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                        int offset, BluetoothGattDescriptor descriptor) {
      super.onDescriptorReadRequest(device, requestId, offset, descriptor);
      if (ActivityCompat.checkSelfPermission(Peripheral.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "Permission check failed in onDescriptorReadRequest");
        return;
      }
      Log.d(TAG, "Device tried to read descriptor: " + descriptor.getUuid());
      Log.d(TAG, "Value: " + Arrays.toString(descriptor.getValue()));
      if (offset != 0) {
        mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
        return;
      }
      mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
              descriptor.getValue());
    }

    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                         BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                                         int offset,
                                         byte[] value) {
      super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded,
              offset, value);
      if (ActivityCompat.checkSelfPermission(Peripheral.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "Permission check failed in onDescriptorWriteRequest");
        return;
      }
      Log.v(TAG, "Descriptor Write Request " + descriptor.getUuid() + " " + Arrays.toString(value));
      int status = BluetoothGatt.GATT_SUCCESS;
      if (descriptor.getUuid().equals(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        boolean supportsNotifications = (characteristic.getProperties() &
                BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        boolean supportsIndications = (characteristic.getProperties() &
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

        if (!(supportsNotifications || supportsIndications)) {
          status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        } else if (value.length != 2) {
          status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
        } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
          status = BluetoothGatt.GATT_SUCCESS;
          mCurrentServiceFragment.notificationsDisabled(characteristic);
          descriptor.setValue(value);
        } else if (supportsNotifications &&
                Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
          status = BluetoothGatt.GATT_SUCCESS;
          mCurrentServiceFragment.notificationsEnabled(characteristic, false /* indicate */);
          descriptor.setValue(value);
        } else if (supportsIndications &&
                Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
          status = BluetoothGatt.GATT_SUCCESS;
          mCurrentServiceFragment.notificationsEnabled(characteristic, true /* indicate */);
          descriptor.setValue(value);
        } else {
          status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        }
      } else {
        status = BluetoothGatt.GATT_SUCCESS;
        descriptor.setValue(value);
      }
      if (responseNeeded) {
        mGattServer.sendResponse(device, requestId, status, 0, null);
      }
    }
  };


  //////////////////////////////////////////////////
  ////// Static Descriptor Helper Methods //////////
  //////////////////////////////////////////////////

  public static BluetoothGattDescriptor getClientCharacteristicConfigurationDescriptor() {
    BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
            (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
    descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
    return descriptor;
  }

  public static BluetoothGattDescriptor getCharacteristicUserDescriptionDescriptor(
          String characteristicUserDescription) {
    BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
            CHARACTERISTIC_USER_DESCRIPTION_UUID,
            (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
    try {
      descriptor.setValue(characteristicUserDescription.getBytes("UTF-8"));
    } catch (UnsupportedEncodingException e) {
      Log.e(TAG, "Unable to convert characteristic description to byte array");
    }
    return descriptor;
  }


  /////////////////////////////////
  ////// Lifecycle Callbacks //////
  /////////////////////////////////

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_peripherals);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    mAdvStatus = (TextView) findViewById(R.id.textView_advertisingStatus);
    mConnectionStatus = (TextView) findViewById(R.id.textView_connectionStatus);
    mBluetoothDevices = new HashSet<>();
    mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    mBluetoothAdapter = mBluetoothManager.getAdapter();

    if (savedInstanceState == null) {
      int peripheralIndex = getIntent().getIntExtra(Peripherals.EXTRA_PERIPHERAL_INDEX, -1);
      if (peripheralIndex == 0) {
        mCurrentServiceFragment = new BatteryServiceFragment();
      } else if (peripheralIndex == 1) {
        mCurrentServiceFragment = new HeartRateServiceFragment();
      } else if (peripheralIndex == 2) {
        mCurrentServiceFragment = new HealthThermometerServiceFragment();
      } else {
        Log.wtf(TAG, "Service doesn't exist");
      }
      getFragmentManager()
              .beginTransaction()
              .add(R.id.fragment_container, mCurrentServiceFragment, CURRENT_FRAGMENT_TAG)
              .commit();
    } else {
      mCurrentServiceFragment = (ServiceFragment) getFragmentManager()
              .findFragmentByTag(CURRENT_FRAGMENT_TAG);
    }
    mBluetoothGattService = mCurrentServiceFragment.getBluetoothGattService();

    mAdvSettings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build();
    mAdvData = new AdvertiseData.Builder()
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(mCurrentServiceFragment.getServiceUUID())
            .build();
    mAdvScanResponse = new AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.menu_peripheral, menu);
    return true;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_ENABLE_BT) {
      if (resultCode == RESULT_OK) {
        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
          Toast.makeText(this, R.string.bluetoothAdvertisingNotSupported, Toast.LENGTH_LONG).show();
          Log.e(TAG, "Advertising not supported");
        }
      } else {
        Toast.makeText(this, R.string.bluetoothNotEnabled, Toast.LENGTH_LONG).show();
        Log.e(TAG, "Bluetooth not enabled");
        finish();
      }
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    resetStatusViews();
    // Check and request permissions, then start the service
    if (!hasRequiredPermissions()) {
      requestRequiredPermissions();
    } else {
      startBluetoothService();
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.action_disconnect_devices) {
      disconnectFromDevices();
      return true;
    }
    return false;
  }

  @Override
  protected void onStop() {
    super.onStop();
    // Stop advertising
    if (mAdvertiser != null) {
      if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
        mAdvertiser.stopAdvertising(mAdvCallback);
      } else {
        Log.w(TAG, "No BLUETOOTH_ADVERTISE permission to stop advertising.");
      }
    }
    // Close GATT server
    if (mGattServer != null) {
      if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
        mGattServer.close();
      } else {
        Log.w(TAG, "No BLUETOOTH_CONNECT permission to close GATT server.");
      }
    }
    resetStatusViews();
  }

  @Override
  public void sendNotificationToDevices(BluetoothGattCharacteristic characteristic) {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "No BLUETOOTH_CONNECT permission to send notification.");
      return;
    }
    boolean indicate = (characteristic.getProperties()
            & BluetoothGattCharacteristic.PROPERTY_INDICATE)
            == BluetoothGattCharacteristic.PROPERTY_INDICATE;
    for (BluetoothDevice device : mBluetoothDevices) {
      mGattServer.notifyCharacteristicChanged(device, characteristic, indicate);
    }
  }

  //////////////////////////////////
  ////// Permission Methods ////////
  //////////////////////////////////

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQUEST_ALL_PERMISSIONS) {
      boolean allGranted = true;
      for (int result : grantResults) {
        if (result != PackageManager.PERMISSION_GRANTED) {
          allGranted = false;
          break;
        }
      }
      if (allGranted) {
        startBluetoothService();
      } else {
        Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_LONG).show();
        Log.e(TAG, "Bluetooth permissions not granted.");
        finish();
      }
    }
  }

  private boolean hasRequiredPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
              && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    return true; // Permissions are granted by default on older versions
  }

  private void requestRequiredPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      List<String> permissionsToRequest = new ArrayList<>();
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE);
      }
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
      }
      if (!permissionsToRequest.isEmpty()) {
        ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_ALL_PERMISSIONS);
      }
    }
  }

  ///////////////////////////////
  ////// Private Methods ////////
  ///////////////////////////////

  private void startBluetoothService() {
    if (!ensureBleFeaturesAvailable()) {
      return;
    }
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "No BLUETOOTH_CONNECT permission to start GATT server.");
      return; // Should not happen if permissions are handled correctly
    }
    if (mGattServer == null) {
      mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
      if (mGattServer == null) {
        Log.e(TAG, "Unable to open GATT server.");
        return;
      }
      mGattServer.addService(mBluetoothGattService);
    }

    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "No BLUETOOTH_ADVERTISE permission to start advertising.");
      return; // Should not happen if permissions are handled correctly
    }
    if (mBluetoothAdapter.isMultipleAdvertisementSupported()) {
      if (mAdvertiser == null) {
        mAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
      }
      mAdvertiser.startAdvertising(mAdvSettings, mAdvData, mAdvScanResponse, mAdvCallback);
    } else {
      mAdvStatus.setText(R.string.status_noLeAdv);
    }
  }

  private void resetStatusViews() {
    mAdvStatus.setText(R.string.status_notAdvertising);
    updateConnectedDevicesStatus();
  }

  private void updateConnectedDevicesStatus() {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      mConnectionStatus.setText(R.string.status_permission_denied);
      return;
    }
    final String message = getString(R.string.status_devicesConnected) + " "
            + mBluetoothManager.getConnectedDevices(BluetoothGattServer.GATT).size();
    runOnUiThread(() -> mConnectionStatus.setText(message));
  }

  private void disconnectFromDevices() {
    Log.d(TAG, "Disconnecting devices...");
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "No BLUETOOTH_CONNECT permission to disconnect devices.");
      return;
    }
    if (mGattServer == null) {
      return;
    }
    for (BluetoothDevice device : mBluetoothManager.getConnectedDevices(BluetoothGattServer.GATT)) {
      Log.d(TAG, "Disconnecting device: " + device.getAddress());
      mGattServer.cancelConnection(device);
    }
  }

  private boolean ensureBleFeaturesAvailable() {
    if (mBluetoothAdapter == null) {
      Toast.makeText(this, R.string.bluetoothNotSupported, Toast.LENGTH_LONG).show();
      Log.e(TAG, "Bluetooth not supported");
      finish();
      return false;
    }
    if (!mBluetoothAdapter.isEnabled()) {
      if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "No BLUETOOTH_CONNECT permission to request enabling Bluetooth.");
        // We must request permission before we can request to enable Bluetooth
        requestRequiredPermissions();
        return false;
      }
      Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
      startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
      return false;
    }
    return true;
  }
}
