package com.supermap.imobilelite.spatialAnalyst;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.restlet.data.MediaType;

import android.util.Log;

import com.supermap.imobilelite.commons.Credential;
import com.supermap.imobilelite.commons.EventStatus;
import com.supermap.imobilelite.commons.utils.ServicesUtil;
import com.supermap.imobilelite.maps.Constants;
import com.supermap.imobilelite.maps.Util;
import com.supermap.imobilelite.resources.SpatialAnalystCommon;
import com.supermap.services.rest.encoders.JsonEncoder;
import com.supermap.services.rest.util.JsonConverter;
import com.supermap.services.util.ResourceManager;

/**
 * <p>
 * 数据集缓冲区分析服务类。
 * </p>
 * <p>
 * 数据集缓冲区分析是指对数据集中符合条件的要素做缓冲区分析。该类负责将客户设置的数据集缓冲区分析参数传递给服务端，并接收服务端返回的数据集缓冲区分析结果数据。
 * 缓冲区分析是 GIS 中基本的空间分析，缓冲区实际上是在基本空间要素周围建立的具有一定宽度的邻近区域。缓冲区分析可以有以下应用，比如确定街道拓宽的范围，确定放射源影响的范围等等。
 * </p>
 * @author ${Author}
 * @version ${Version}
 * 
 */
public class DatasetBufferAnalystService {
    private ExecutorService executors = Executors.newFixedThreadPool(5);
    private static final String LOG_TAG = "com.supermap.imobilelite.data.DatasetBufferAnalystservice";
    private static ResourceManager resource = new ResourceManager("com.supermap.imobilelite.SpatialAnalystCommon");
    private DatasetBufferAnalystResult lastResult;
    private String baseUrl;
    private int timeout = -1; // 代表使用默认超时时间，5秒

    /**
     * <p>
     * 构造函数。
     * </p>
     * @param url 数据集缓冲区分析服务地址。如 http://ServerIP:8090/iserver/services/spatialanalyst-sample/restjsr/spatialanalyst
     */
    public DatasetBufferAnalystService(String url) {
        super();
        baseUrl = ServicesUtil.getFormatUrl(url);
        lastResult = new DatasetBufferAnalystResult();
    }

    /**
     * <p>
     * 根据数据集缓冲区分析与服务端完成异步通讯，即发送分析参数，并通过实现DatasetBufferAnalystEventListener监听器处理分析结果。
     * </p>
     * @param <T>
     * @param params 数据集缓冲区分析参数信息。
     * @param listener 处理分析结果的DatasetBufferAnalystEventListener监听器。
     */
    public <T> void process(DatasetBufferAnalystParameters params, DatasetBufferAnalystEventListener listener) {
        if (StringUtils.isEmpty(baseUrl) || params == null) {
            return;
        }
        if (StringUtils.isEmpty(params.dataset)) {
            return;
        }

        Future<?> future = this.executors.submit(new DoDatasetBufferAnalystTask(params, listener));
        listener.setProcessFuture(future);
    }

    /**
     * <p>
     * 用户自定义超时时间。
     * </p>
     * @param timeout 用户自定义超时时间。若用户不设置，则使用默认超时间为5秒。0代表无限，即代表不设置超时限制。单位默认为秒。
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * <p>
     * 重新组装请求的地址，发送请求并处理请求。
     * </p>
     * @param <T>
     * @param params 数据集缓冲区分析参数信息。
     * @return 返回数据集缓冲区分析结果。
     * @throws IOException
     */
    private <T> DatasetBufferAnalystResult doDatasetBufferAnalyst(DatasetBufferAnalystParameters params, DatasetBufferAnalystEventListener listener)
            throws IOException {
        // 对数据集名称编码
        String datasetStr = String.valueOf(params.dataset);
        datasetStr = URLEncoder.encode(datasetStr, Constants.UTF8);
        // 请求体参数
        HashMap<String, Object> queryEntity = new HashMap<String, Object>();
        queryEntity.put("isAttributeRetained", params.isAttributeRetained);
        queryEntity.put("isUnion", params.isUnion);
        queryEntity.put("bufferAnalystParameter", params.buffersetting);
        queryEntity.put("filterQueryParameter", params.filterQueryParameter);
        queryEntity.put("dataReturnOption", params.resultSetting);
        JsonEncoder encoder = new JsonEncoder();
        String queryText = encoder.toRepresentation(MediaType.APPLICATION_JSON, queryEntity).getText();
        // URI参数
        List<NameValuePair> paramList = new ArrayList<NameValuePair>();
        paramList.add(new BasicNameValuePair("asynchronousReturn", "false"));
        paramList.add(new BasicNameValuePair("returnContent", "true"));
        if (Credential.CREDENTIAL != null) {
            paramList.add(new BasicNameValuePair(Credential.CREDENTIAL.name, Credential.CREDENTIAL.value));
        }
        String serviceUrl = baseUrl + "/datasets/" + datasetStr + "/buffer.json?" + URLEncodedUtils.format(paramList, HTTP.UTF_8);// 参数编码
        try {
            String resultStr = Util.post(serviceUrl, Util.newJsonUTF8StringEntity(queryText), this.timeout);
            // 请求返回成功，则解析结果。请求失败返回null，则lastResult直接用new的空对象
            if (!StringUtils.isEmpty(resultStr)) {
                JsonConverter jsConverer = new JsonConverter();
                lastResult = jsConverer.to(resultStr, DatasetBufferAnalystResult.class);
            }
            listener.onDatasetBufferAnalystStatusChanged(lastResult, EventStatus.PROCESS_COMPLETE);
        } catch (Exception e) {
            listener.onDatasetBufferAnalystStatusChanged(lastResult, EventStatus.PROCESS_FAILED);
            Log.w(LOG_TAG, resource.getMessage(this.getClass().getSimpleName(), SpatialAnalystCommon.SPATIALANALYST_EXCEPTION, e.getMessage()));
        }

        // 发送请求返回结果
        return lastResult;
    }

    /**
     * <p>
     * 返回数据集缓冲区分析结果。
     * </p>
     * @return 分析结果。
     */
    public DatasetBufferAnalystResult getLastResult() {
        // 发送请求返回结果
        return lastResult;
    }

    /**
     * <p>
     * 处理数据集缓冲区分析结果的监听器抽象类。
     * 提供了等待 监听器执行完毕的接口。
     * </p>
     * @author ${Author}
     * @version ${Version}
     * 
     */
    public static abstract class DatasetBufferAnalystEventListener {
        private AtomicBoolean processed = new AtomicBoolean(false);
        private Future<?> future;

        /**
         * <p>
         * 用户必须自定义实现处理分析结果的接口。
         * </p>
         * @param sourceObject 分析结果。
         * @param status 分析结果的状态。
         */
        public abstract void onDatasetBufferAnalystStatusChanged(Object sourceObject, EventStatus status);

        private boolean isProcessed() {
            return processed.get();
        }

        /**
         * <p>
         * 设置异步操作处理。
         * </p>
         * @param future Future对象。
         */
        protected void setProcessFuture(Future<?> future) {
            this.future = future;
        }

        /**
         * <p>
         * 等待监听器执行完毕。
         * </p>
         * @throws InterruptedException
         * @throws ExecutionException
         */
        public void waitUntilProcessed() throws InterruptedException, ExecutionException {
            if (future == null) {
                return;
            }
            future.get();
        }
    }

    class DoDatasetBufferAnalystTask<T> implements Runnable {
        private DatasetBufferAnalystParameters params;
        private DatasetBufferAnalystEventListener listener;

        DoDatasetBufferAnalystTask(DatasetBufferAnalystParameters params, DatasetBufferAnalystEventListener listener) {
            this.params = params;
            this.listener = listener;
        }

        public void run() {
            try {
                doDatasetBufferAnalyst(params, listener);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
}
