package org.mutils.wechat.wechatpay.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.SortedMap;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jdom.JDOMException;
import org.mutils.wechat.wechatpay.core.model.BaseWeChatPayModel;
import org.mutils.wechat.wechatpay.core.model.NotifyModel;
import org.mutils.wechat.wechatpay.core.model.RefundModel;
import org.mutils.wechat.wechatpay.core.model.RefundReturnModel;
import org.mutils.wechat.wechatpay.core.model.WithdrawModel;
import org.mutils.wechat.wechatpay.core.util.ParseXmlUtil;
import org.mutils.wechat.wechatpay.core.util.SignUtil;

import cn.minsin.core.exception.MutilsErrorException;
import cn.minsin.core.init.WechatPayCoreConfig;
import cn.minsin.core.init.core.AbstractConfig;
import cn.minsin.core.rule.AbstractFunctionRule;
import cn.minsin.core.tools.HttpClientUtil;
import cn.minsin.core.tools.IOUtil;
import cn.minsin.core.tools.MapUtil;

/**
 * 	微信配置文件(微信支付，微信公众号)
 * @author mintonzhang
 * @date 2018年6月22日
 */
public class WeChatPayFunctions extends AbstractFunctionRule {

	protected final static WechatPayCoreConfig payconfig = AbstractConfig.loadConfig(WechatPayCoreConfig.class);

	/**
	 * 	发起微信转账(提现)
	 * 
	 * @param model 发起提现的包装类
	 * @return
	 * @throws MutilsErrorException
	 * @throws IOException 
	 * @throws ClientProtocolException 
	 * @throws JDOMException 
	 */
	public static Map<String, String> createWithdrawXml(WithdrawModel model) throws MutilsErrorException, ClientProtocolException, IOException, JDOMException   {
		String xml = model.toXml(payconfig.getPartnerKey());
		LOGGER.info("withdraw xml is {}", xml);
		CloseableHttpClient httpclient = null;
		CloseableHttpResponse response = null;
		try {
			httpclient = HttpClientUtil.getSSLInstance(payconfig.getPartnerId(), payconfig.getCertificatePath(),
					payconfig.getCertificateFormat());
			HttpPost httpost = HttpClientUtil.getPostMethod(payconfig.getWithdrawUrl());

			httpost.setEntity(new StringEntity(xml, "UTF-8"));
			response = httpclient.execute(httpost);
			String jsonStr = EntityUtils.toString(response.getEntity(), "UTF-8");
			LOGGER.info("withdraw json is {}", jsonStr);
			return ParseXmlUtil.doXMLParse(jsonStr);
		} finally {
			IOUtil.close(httpclient, response);
		}
	}

	/**
	 * 	发起退款申请
	 * 
	 * @param model
	 * @return
	 * @throws MutilsErrorException
	 * @throws IOException 
	 * @throws ClientProtocolException 
	 * @throws JDOMException 
	 */
	protected static RefundReturnModel createRefundRequest(RefundModel model) throws MutilsErrorException, JDOMException, ClientProtocolException, IOException {
		String xmlParam = model.toXml(payconfig.getPartnerKey());
		LOGGER.info("refund xml is {}", xmlParam);
		CloseableHttpClient httpclient = null;
		CloseableHttpResponse response = null;
		try {
			httpclient = HttpClientUtil.getSSLInstance(payconfig.getPartnerId(), payconfig.getCertificatePath(),
					payconfig.getCertificateFormat());
			HttpPost httpost = HttpClientUtil.getPostMethod(payconfig.getRefundUrl());
			httpost.setEntity(new StringEntity(xmlParam, "UTF-8"));
			response = httpclient.execute(httpost);

			String jsonStr = EntityUtils.toString(response.getEntity(), "UTF-8");
			LOGGER.info("refund json is {}", jsonStr);
			return MapUtil.mapToObject(ParseXmlUtil.doXMLParse(jsonStr), RefundReturnModel.class);
		}  finally {
			IOUtil.close(httpclient, response);
		}
	}

	/**
	 * 	统一下单接口 用于生成 预支付id 及二维码id
	 * 
	 * @param model 预下单的对象
	 * @return
	 * @throws ParseException 
	 * @throws MutilsErrorException
	 * @throws IOException
	 * @throws JDOMException 
	 */
	protected static Map<String, String> createUnifiedOrder(BaseWeChatPayModel model) throws ParseException, IOException, MutilsErrorException, JDOMException {
		CloseableHttpClient httpclient = HttpClientUtil.getInstance();// 先初始化;
		CloseableHttpResponse response = null;

		try {
			HttpPost httpost = HttpClientUtil.getPostMethod(payconfig.getUnifiedOrderUrl());
			String xmlParam = model.toXml(payconfig.getPartnerKey());
			LOGGER.info("createUnifiedOrder xml is {}", xmlParam);
			httpost.setEntity(new StringEntity(xmlParam, "UTF-8"));
			response = httpclient.execute(httpost);
			String jsonStr = EntityUtils.toString(response.getEntity(), "UTF-8");
			LOGGER.info("createUnifiedOrder json is {}", jsonStr);
			if (jsonStr.indexOf("FAIL") != -1) {
				throw new MutilsErrorException(jsonStr);
			}
			return ParseXmlUtil.doXMLParse(jsonStr);
		} finally {
			IOUtil.close(httpclient, response);
		}

	}

	protected static boolean checkMap(Map<String, String> doXMLParse) throws MutilsErrorException {
		if (doXMLParse == null || doXMLParse.isEmpty()) {
			throw new MutilsErrorException(
					"统一支付XML生成失败,无法进行下一步操作. The value from createUnifiedOrder method is null,please check the parameters.");
		}
		return true;
	}

	/**
	 * 生成签名
	 * 
	 * @param sortMap
	 * @return
	 */
	protected static String createSign(SortedMap<String, String> sortMap) {
		return SignUtil.createSign(sortMap, payconfig.getPartnerKey());
	}

	/**
	 * 	微信支付回调解析
	 * <xml><return_code><![CDATA[STATE]]></return_code><return_msg><![CDATA[OK]]></return_msg></xml>
	 * 	如果成功 将STATE替换为SUCCESS 如果失败替换为FAIL 反馈给微信服务器不用再重复请求。 使用PrintWriter.println直接输出
	 * 
	 * @param req
	 * @throws IOException 
	 * @throws JDOMException 
	 * @throws MutilsErrorException
	 */
	public static NotifyModel parseNotify(HttpServletRequest req) throws IOException, JDOMException, MutilsErrorException  {
		BufferedReader br =null;
		InputStreamReader inputStreamReader =null;
		try {
			 inputStreamReader = new InputStreamReader(req.getInputStream());
			 br = new BufferedReader(inputStreamReader);
			String line = null;
			StringBuilder sb = new StringBuilder();
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			br.close();
			Map<String, String> map = ParseXmlUtil.doXMLParse(sb.toString());
			return MapUtil.mapToObject(map, NotifyModel.class);
		}finally {
			IOUtil.close(br,inputStreamReader);
		}

	}
}
