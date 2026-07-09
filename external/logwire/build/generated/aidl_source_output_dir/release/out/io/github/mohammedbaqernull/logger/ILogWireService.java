/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /data/data/com.termux/files/home/android-sdk/build-tools/35.0.0/aidl -p/data/data/com.termux/files/home/android-sdk/platforms/android-36/framework.aidl -o/data/data/com.termux/files/home/MinAndroidProjects/TomAndroidIDE/external/logwire/build/generated/aidl_source_output_dir/debug/out -I/data/data/com.termux/files/home/MinAndroidProjects/TomAndroidIDE/external/logwire/src/main/aidl -I/data/data/com.termux/files/home/MinAndroidProjects/TomAndroidIDE/external/logwire/src/debug/aidl -d/data/data/com.termux/files/usr/tmp/aidl5548789425125377929.d /data/data/com.termux/files/home/MinAndroidProjects/TomAndroidIDE/external/logwire/src/main/aidl/io/github/mohammedbaqernull/logger/ILogWireService.aidl
 */
package io.github.mohammedbaqernull.logger;
public interface ILogWireService extends android.os.IInterface
{
  /** Default implementation for ILogWireService. */
  public static class Default implements io.github.mohammedbaqernull.logger.ILogWireService
  {
    @Override public void sendLog(java.lang.String packageName, java.lang.String tag, java.lang.String message, int level, long timestamp) throws android.os.RemoteException
    {
    }
    @Override public void registerApp(java.lang.String packageName) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements io.github.mohammedbaqernull.logger.ILogWireService
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an io.github.mohammedbaqernull.logger.ILogWireService interface,
     * generating a proxy if needed.
     */
    public static io.github.mohammedbaqernull.logger.ILogWireService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof io.github.mohammedbaqernull.logger.ILogWireService))) {
        return ((io.github.mohammedbaqernull.logger.ILogWireService)iin);
      }
      return new io.github.mohammedbaqernull.logger.ILogWireService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_sendLog:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          java.lang.String _arg2;
          _arg2 = data.readString();
          int _arg3;
          _arg3 = data.readInt();
          long _arg4;
          _arg4 = data.readLong();
          this.sendLog(_arg0, _arg1, _arg2, _arg3, _arg4);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_registerApp:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.registerApp(_arg0);
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements io.github.mohammedbaqernull.logger.ILogWireService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public void sendLog(java.lang.String packageName, java.lang.String tag, java.lang.String message, int level, long timestamp) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          _data.writeString(tag);
          _data.writeString(message);
          _data.writeInt(level);
          _data.writeLong(timestamp);
          boolean _status = mRemote.transact(Stub.TRANSACTION_sendLog, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void registerApp(java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_registerApp, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_sendLog = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_registerApp = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "io.github.mohammedbaqernull.logger.ILogWireService";
  public void sendLog(java.lang.String packageName, java.lang.String tag, java.lang.String message, int level, long timestamp) throws android.os.RemoteException;
  public void registerApp(java.lang.String packageName) throws android.os.RemoteException;
}
