package cn.xnatural.http;

import java.io.*;
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
     * 把文件写入到目录
     * @param dir 目录
     */
    public void transferTo(File dir) throws Exception {
        if (dir == null) throw new IllegalArgumentException("Param dir required");
        if (!dir.isDirectory()) throw new IllegalArgumentException("Param dir must be a directory");
        dir.mkdirs();
        File f = new File(dir, finalName);
        if (!f.exists()) f.createNewFile();
        byte[] bs = new byte[((Supplier<Integer>) () -> {
            if (size > 1024 * 1024 * 100) return 1024 * 1024 * 10;
            if (size > 1024 * 1024 * 50) return 1024 * 1024 * 6;
            if (size > 1024 * 1024 * 10) return 1024 * 1024;
            if (size > 1024 * 1024 * 5) return 1024 * 600;
            if (size > 1024 * 1024) return 1024 * 200;
            return 1024 * 20;
        }).get()];
        try (OutputStream fos = new FileOutputStream(f)) {
            while (true) {
                int length = getInputStream().read(bs);
                if (length == -1) break;
                fos.write(bs, 0, length);
            }
        }
    }


    /**
     * 追加到另一个文件
     * @param targetFile 目标文件
     */
    public void appendTo(File targetFile) throws Exception {
        if (targetFile == null) throw new IllegalArgumentException("Param targetFile required");
        if (targetFile.isDirectory()) throw new IllegalArgumentException("Param targetFile must be a file");
        if (!targetFile.exists()) { //不存在则创建
            targetFile.getParentFile().mkdirs();
            targetFile.createNewFile();
        }
        byte[] bs = new byte[((Supplier<Integer>) () -> {
            if (size > 1024 * 1024 * 100) return 1024 * 1024 * 10;
            if (size > 1024 * 1024 * 50) return 1024 * 1024 * 6;
            if (size > 1024 * 1024 * 10) return 1024 * 1024;
            if (size > 1024 * 1024 * 5) return 1024 * 600;
            if (size > 1024 * 1024) return 1024 * 200;
            return 1024 * 20;
        }).get()];
        try (OutputStream fos = new FileOutputStream(targetFile, true)) {
            while (true) {
                int length = getInputStream().read(bs);
                if (length == -1) break;
                fos.write(bs, 0, length);
            }
        }
    }


    /**
     * 删除
     */
    public void delete() {
        if (inputStream != null) {
            try { inputStream.close(); } catch (IOException e) {/** ignore **/}
        }
        inputStream = null; size = null;
        if (file != null) file.delete();
    }


    @Override
    public String toString() {
        return FileData.class.getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[finalName=" + finalName + ", originName=" + originName + ", size=" + size + ", extension=" + extension +"]";
    }


    public String getOriginName() { return originName; }

    public String getFinalName() { return finalName; }

    public FileData setFinalName(String finalName) {
        this.finalName = finalName;
        return this;
    }

    public InputStream getInputStream() throws FileNotFoundException {
        if (inputStream == null && file != null) {
            inputStream = new FileInputStream(file);
        }
        return inputStream;
    }

    public FileData setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
        return this;
    }

    public Long getSize() { return size; }

    public FileData setSize(Long size) {
        this.size = size;
        return this;
    }

    public String getExtension() { return extension; }

    public FileData setExtension(String extension) {
        this.extension = extension;
        return this;
    }

    public File getFile() { return file; }

    public FileData setFile(File file) {
        this.file = file;
        setSize(file.length());
        return this;
    }
}
