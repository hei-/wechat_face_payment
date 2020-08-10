package com.parsec.wechat_face_payment

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.annotation.NonNull
import com.tencent.wxpayface.IWxPayfaceCallback
import com.tencent.wxpayface.ReturnXMLParser
import com.tencent.wxpayface.WxPayFace
import com.tencent.wxpayface.WxfacePayCommonCode
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import okhttp3.*
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


/** WechatFacePaymentPlugin */
public class WechatFacePaymentPlugin : FlutterPlugin, MethodCallHandler {

    private val PARAMS_FACE_AUTHTYPE = "face_authtype"
    private val PARAMS_APPID = "appid"
    private val PARAMS_SUB_APPID = "sub_appid"
    private val PARAMS_MCH_ID = "mch_id"
    private val PARAMS_MCH_NAME = "mch_name"
    private val PARAMS_SUB_MCH_ID = "sub_mch_id"
    private val PARAMS_STORE_ID = "store_id"
    private val PARAMS_AUTHINFO = "authinfo"
    private val PARAMS_FACE_SID = "face_sid"
    private val PARAMS_INFO_TYPE = "info_type"
    private val PARAMS_OUT_TRADE_NO = "out_trade_no"
    private val PARAMS_TOTAL_FEE = "total_fee"
    private val PARAMS_TELEPHONE = "telephone"

    /*
     * 微信刷脸SDK返回常量
     */
    private val RETURN_CODE = "return_code"
    private val RETURN_SUCCESS = "SUCCESS"
    private val RETURN_FAILE = "SUCCESS"
    private val RETURN_MSG = "return_msg"

    /*
    刷脸支付相关参数
     */
    private lateinit var appId: String //商户公众号或小程序APPIdD
    private lateinit var mchId: String //商户ID
    private lateinit var storeId: String // 店铺ID
    private lateinit var telPhone: String //用户手机号
    private lateinit var openId: String //用户微信OPENID
    private lateinit var outTradeNo: String //外部订单号
    private lateinit var totalFee: String // 支付金额
    private lateinit var faceAuthType: String //FACEPAY 人脸凭证  FACEPAY_DELAY 延迟支付 FACE_AUTH 实名认证 FACEID-ONCE 人脸识别(单次模式) FACEID-LOOP 人脸识别(循环模式) SCAN_CODE  扫码支付

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.getFlutterEngine().getDartExecutor(), "wechat_face_payment")
        channel.setMethodCallHandler(this);
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {
        val tag = "WeChatFacePaymentPlugin"
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "wechat_face_payment")
            channel.setMethodCallHandler(WechatFacePaymentPlugin())
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "initFacePay" -> {
                appId = call.argument<String>("appId")!!
                mchId = call.argument<String>("mchId")!!
                storeId = call.argument<String>("storeId")!!
                telPhone = call.argument<String>("telPhone")!!
                openId = call.argument<String>("openId")!!
                outTradeNo = call.argument<String>("outTradeNo")!!
                totalFee = call.argument<String>("totalFee")!!
                faceAuthType = call.argument<String>("faceAuthType")!!
                initFacePay()
                result.success("initFacePay SUCCESS")
            }
            "initScanCodePay" -> { //  扫码支付
                initScanCodePay(result);
            }
            "releaseWxPayFace" -> { //  释放资源
                WxPayFace.getInstance().releaseWxpayface(context);
                result.success("SUCCESS")
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    /**
     * 初始化微信刷脸支付
     */
    private fun initFacePay() {
        WxPayFace.getInstance().initWxpayface(context, object : IWxPayfaceCallback() {
            override fun response(info: MutableMap<Any?, Any?>?) {
                if (!isSuccessInfo(info)) {
                    return
                }
                Log.d(tag, "微信刷脸支付初始化完成")
                getRawData();
            }
        })
    }

    /**
     *  初始化扫码支付
     */
    private fun initScanCodePay(result: Result){
        WxPayFace.getInstance().initWxpayface(context, object : IWxPayfaceCallback() {
            override fun response(info: MutableMap<Any?, Any?>?) {
                if (!isSuccessInfo(info)) {
                    return
                }
                Log.d(tag, "微信刷脸支付初始化完成 开始启用 扫码支付")
                WxPayFace.getInstance().startCodeScanner(object : IWxPayfaceCallback() {
                    @Throws(RemoteException::class)
                    override fun response(info: Map<*, *>) {
                        val returnCode = info["return_code"].toString()
                        val errCode = info["err_code"].toString()
                        val returnMsg = info["return_msg"].toString() // 对错误码的描述
                        val codeMsg = info["code_msg"].toString() // 当扫码成功时返回扫码内容
                        if (!isSuccessInfo(info)) {
                            result.error(errCode, returnMsg, info)
                            return
                        }
                        /**
                         *关闭扫码
                         */
                        WxPayFace.getInstance().stopCodeScanner();
                        try {
                            val msg = "startCodeScanner, /\n return_code : $returnCode /\n return_msg : $returnMsg /\n code_msg: $codeMsg /\n err_code:$errCode";
                            Log.d(tag, "扫码完成:$msg")
                            result.success(info);
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                })
            }
        })
    }

    /**
     * 获取数据getWxpayfaceRawdata
     * 接口作用：获取rawdata数据
     */
    private fun getRawData() {

        /**
         * https://pay.weixin.qq.com/wiki/doc/wxfacepay/develop/android/faceuser.html#_2%E3%80%81%E8%8E%B7%E5%8F%96%E6%95%B0%E6%8D%AE
         */
        WxPayFace.getInstance().getWxpayfaceRawdata(object : IWxPayfaceCallback() {
            @Throws(RemoteException::class)
            override fun response(info: Map<*, *>) {
                if (!isSuccessInfo(info)) {
                    return
                }
                val rawData = info["rawdata"].toString()
                Log.d(tag, "取得RawData:$rawData");
                try {
                    getAuthInfo(rawData)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        })
    }

    /**
     * 取得刷脸支付授权信息
     * 获取调用凭证get_wxpayface_authinfo(rawdata)（获取调用凭证）
     * 接口作用：获取调用凭证

     * 接口地址：https://payapp.weixin.qq.com/face/get_wxpayface_authinfo
     */
    @Throws(IOException::class)
    private fun getAuthInfo(rawData: String) {
        var authInfo = "";
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        @Throws(CertificateException::class)
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                        }

                        @Throws(CertificateException::class)
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate> {
                            return arrayOf()
                        }
                    }
            )

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory
            val client = OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory)
                    .hostnameVerifier { hostname, session -> true }
                    .build()
            val body = RequestBody.create(null, rawData)
            val request = Request.Builder()
                    .url("https://wxpay.wxutil.com/wxfacepay/api/getWxpayFaceAuthInfo.php")
                    .post(body)
                    .build()
            client.newCall(request)
                    .enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            println("onFailure | getAuthInfo $e")
                        }

                        @Throws(IOException::class)
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                authInfo = ReturnXMLParser.parseGetAuthInfoXML(response.body()!!.byteStream())
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            Log.i(tag, "取得AuthInfo:$authInfo")

                            getWxpayfaceCode(authInfo);
                        }
                    })
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }

    /**
     * 进行人脸识别getWxpayfaceCode（获取用户信息）
     * 接口作用：启动人脸APP主界面入口，开启人脸识别，获取支付凭证或用户信息。
     *
     *  (获取用户信息)
     */
    private fun getWxpayfaceCode(authInfo: String) {
        val params: HashMap<String, String> = HashMap<String, String>()
        params[PARAMS_FACE_AUTHTYPE] = faceAuthType
        params[PARAMS_APPID] = appId
        params[PARAMS_MCH_ID] = mchId
        params[PARAMS_STORE_ID] = storeId
        params[PARAMS_OUT_TRADE_NO] = "" + System.currentTimeMillis() / 100000
        params[PARAMS_TOTAL_FEE] = totalFee
        params[PARAMS_TELEPHONE] = telPhone
        params[PARAMS_AUTHINFO] = authInfo

        WxPayFace.getInstance().getWxpayfaceCode(params, object : IWxPayfaceCallback() {
            @Throws(RemoteException::class)
            override fun response(info: Map<*, *>) {
                if (!isSuccessInfo(info)) {
                    return
                }
                Log.i(tag, "response | getWxPayFaceCode")

                when (info[RETURN_CODE] as String?) {
                    WxfacePayCommonCode.VAL_RSP_PARAMS_SUCCESS -> {
                        Log.i(tag, "支付成功")
                    }
                    WxfacePayCommonCode.VAL_RSP_PARAMS_USER_CANCEL -> {
                        Log.i(tag, "用户取消支付")
                    }
                    WxfacePayCommonCode.VAL_RSP_PARAMS_SCAN_PAYMENT -> {
                        Log.i(tag, "扫码支付")
                    }
                    WxfacePayCommonCode.VAL_RSP_PARAMS_ERROR -> {
                        Log.i(tag, "发生错误")
                    }

                }
            }
        })
    }


    /**
     *  请求用户授权认证getWxpayAuth（实名认证授权）
     *  接口作用： 获得用户授权商户获取实名认证信息
     *
     *  authInfo : 调用凭证。获取方式参见: get_wxpayface_authinfo
     *  face_sid : 用户身份信息查询凭证。获取方式见 [getWxpayfaceCode]
     */
    private fun getWxpayAuth(authInfo: String, face_sid: String){
        val params: HashMap<String, String> = HashMap<String, String>()
        params[PARAMS_AUTHINFO] = authInfo
        params[PARAMS_FACE_SID] = face_sid
        WxPayFace.getInstance().getWxpayAuth(params,object: IWxPayfaceCallback() {
            override fun response(info: Map<*, *>) {
                Log.i(tag, "实名认证返回")
                val sb = StringBuilder()
                if (!isSuccessInfo(info)) {
                    return
                }
                val finalResult =sb.append("return_code=").append(info["return_code"].toString()).append(", ").append("return_msg=").append(info["return_msg"].toString()).toString()
                Log.i(tag, "实名认证返回：$finalResult");
            }

        })
    }

    /**
     * 接口作用：查询用户信息
     * 接口地址：https://api.mch.weixin.qq.com/v3/facemch/users
     * 请求方式：GET
     */
    private fun getFaceMchUserInfo(face_sid: String){
        var authInfo = "";
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        @Throws(CertificateException::class)
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                        }

                        @Throws(CertificateException::class)
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate> {
                            return arrayOf()
                        }
                    }
            )

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory
            val client = OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, null)
                    .hostnameVerifier { hostname, session -> true }
                    .build()
            val request = Request.Builder()
                    .url("https://api.mch.weixin.qq.com/v3/facemch/users?=$appId&face_sid=$face_sid&info_type=")
                    .build()
            client.newCall(request)
                    .enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            println("onFailure | getAuthInfo $e")
                        }

                        @Throws(IOException::class)
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                authInfo = ReturnXMLParser.parseGetAuthInfoXML(response.body()!!.byteStream())
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            Log.i(tag, "取得AuthInfo:$authInfo")

                            getWxpayfaceCode(authInfo);
                        }
                    })
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }


    private fun isSuccessInfo(info: Map<*, *>?): Boolean {
        if (info == null) {
            Log.i(tag, "调用返回为空, 请查看日志")
            RuntimeException("调用返回为空").printStackTrace()
            return false
        }
        val code = info[RETURN_CODE] as String?
        val msg = info[RETURN_MSG] as String?
        Log.d(tag, "response | getWxPayFaceRawData $code | $msg")
        if (code == null || code != WxfacePayCommonCode.VAL_RSP_PARAMS_SUCCESS) {
            Log.i(tag, "调用返回非成功信息, 请查看日志")
            RuntimeException("调用返回非成功信息: $msg").printStackTrace()
            return false
        }
        Log.d(tag, "调用返回成功")
        return true
    }
}