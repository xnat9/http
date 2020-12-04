package cn.xnatural.http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 文件
 */
public class FileData {
    /**
     * 原始文件名(包含扩展名)
     */
    private           String      originName;
    /**
     * 文件最终名(系统生成唯一文件名(包含扩展名))
     */
    private           String      finalName;
    /**
     * 文件流
     */
    private transient InputStream inputStream;
    /**
     * 文件对象
     */
    private transient File        file;
    /**
     * 大小
     */
    private           Long        size;
    /**
     * 文件扩展名(后缀)
     */
    private           String      extension;


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


    /**
     * 写入到 目录
     * @param dir
     */
    public void transferTo(File dir) throws Exception {
        dir.mkdirs();
        File f = new File(dir, finalName);
        if (!f.exists()) f.createNewFile();
        byte[] bs = new byte[((Supplier<Integer>) () -> {
            if (size > 1024 * 1024 * 100) return 1024 * 1024 * 5;
            if (size > 1024 * 1024 * 50) return 1024 * 1024 * 2;
            if (size > 1024 * 1024 * 10) return 1024 * 1024 * 1;
            if (size > 1024 * 1024 * 5) return 1024 * 500;
            if (size > 1024 * 1024 * 1) return 1024 * 100;
            return 1024 * 20;
        }).get()];
        try (OutputStream os = new FileOutputStream(f)) {
            while (true) {
                int length = inputStream.read(bs);
                if (length == -1) break;
                os.write(bs, 0, length);
            }
        }
    }


    /**
     * 追加到文件
     * @param targetFile
     */
    public void appendTo(File targetFile) throws Exception {
        if (!targetFile.exists()) { //不存在则创建
            targetFile.getParentFile().mkdirs();
            targetFile.createNewFile();
        }
        byte[] bs = new byte[((Supplier<Integer>) () -> {
            if (size > 1024 * 1024 * 100) return 1024 * 1024 * 5;
            if (size > 1024 * 1024 * 50) return 1024 * 1024 * 2;
            if (size > 1024 * 1024 * 10) return 1024 * 1024 * 1;
            if (size > 1024 * 1024 * 5) return 1024 * 500;
            if (size > 1024 * 1024 * 1) return 1024 * 100;
            return 1024 * 20;
        }).get()];
        try (OutputStream os = new FileOutputStream(targetFile, false)) {
            while (true) {
                int length = inputStream.read(bs);
                if (length == -1) break;
                os.write(bs, 0, length);
            }
        }
    }


    /**
     * 删除
     */
    public void delete() {
        inputStream = null; size = null;
        if (file != null) file.delete();
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

    public File getFile() {
        return file;
    }

    public FileData setFile(File file) {
        this.file = file;
        setSize(file.length());
        return this;
    }
}
