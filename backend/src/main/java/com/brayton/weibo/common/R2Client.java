package com.brayton.weibo.common;

import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.UUID;

@Component
public class R2Client {

    private final S3Client s3;

    @Value("${r2.bucket}")
    private String bucket;

    @Value("${r2.publicBaseUrl}")
    private String publicBaseUrl;

    public R2Client(
            @Value("${r2.accountId}") String accountId,
            @Value("${r2.accessKey}") String accessKey,
            @Value("${r2.secretKey}") String secretKey
    ) {
        String endpoint = "https://" + accountId + ".r2.cloudflarestorage.com";

        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)  // 必须给一个，但真实不会用
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)
                        )
                )
                .forcePathStyle(true)  // Cloudflare 必要！
                .build();
    }

    /**
     * 上传一张已通过校验的图片。key = UUID + 规范扩展名，不再拼接客户端文件名（路径穿越、超长、特殊字符）；
     * Content-Type 取魔数识别出的类型，不用客户端传的值（否则可把 HTML 以 text/html 存进公网桶）
     */
    public String upload(MultipartFile file, ImageType type) {
        String key = UUID.randomUUID() + "." + type.extension();

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(type.mimeType())
                    .build();

            s3.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            return publicBaseUrl + "/" + key;

        } catch (Exception e) {
            throw new WeiboException(CommonErrorCode.R2_UPLOAD_FAILED);
        }
    }
}
