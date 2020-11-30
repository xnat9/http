package cn.xnatural.http;

import java.io.InputStream;
import java.util.UUID;

public class FileData {
    /**
     * 原始文件名(包含扩展名)
     */
    private String                originName;
    /**
     * 文件最终名(系统生成唯一文件名(包含扩展名))
     */
    private String                finalName;
    /**
     * 文件流
     */
    private transient InputStream inputStream;
    /**
     * 大小
     */
    private Long                  size;
    /**
     * 文件扩展名(后缀)
     */
    private String extension;


    public FileData setOriginName(String fName) {
        this.originName = fName;
        String extension = extractFileExtension(fName);
        this.extension = extension;
        String id = UUID.randomUUID().toString().replace("-", "");
        finalName = (extension.isEmpty() ? id: (id + '.' + extension));
        return this;
    }

    /**
     *  返回文件名的扩展名
     * @param fileName
     * @return
     */
    public static String extractFileExtension(String fileName) {
        if (fileName == null) return "";
        int i = fileName.lastIndexOf(".");
        if (i == -1) return "";
        return fileName.substring(i + 1);
    }


    @Override
    public String toString() {
        return FileData.class.getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[finalName=" + finalName + ", originName=" + originName + ", size=" + size + ", extension=" + extension +"]";
    }


    public String getOriginName() {
        return originName;
    }

    public String getFinalName() {
        return finalName;
    }

    public FileData setFinalName(String finalName) {
        this.finalName = finalName;
        return this;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public FileData setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
        return this;
    }

    public Long getSize() {
        return size;
    }

    public FileData setSize(Long size) {
        this.size = size;
        return this;
    }

    public String getExtension() {
        return extension;
    }

    public FileData setExtension(String extension) {
        this.extension = extension;
        return this;
    }
}
