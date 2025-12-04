/**
 * 检查并转换图片为 JPEG 格式
 * 1. 处理 HEIC/HEIF 格式 (使用 heic2any)
 * 2. 处理 WebP/GIF 等 (使用 Canvas 绘制白底)
 * 3. 保持 JPEG/PNG 原样 (或者你可以根据需要在这里也强制转 JPEG)
 */
export const convertToJpegIfNeeded = async (file: File): Promise<File> => {
  // --- 1. 处理 HEIC / HEIF 格式 ---
  // 有些浏览器 file.type 可能是空字符串或 'image/heic'
  const isHeic = 
    file.type === 'image/heic' || 
    file.type === 'image/heif' || 
    file.name.toLowerCase().endsWith('.heic') ||
    file.name.toLowerCase().endsWith('.heif');

  if (isHeic) {
    try {

      const heic2any = (await import('heic2any')).default;
      // heic2any 返回 Blob | Blob[]
      const convertedBlob = await heic2any({
        blob: file,
        toType: 'image/jpeg',
        quality: 0.9,
      });

      // 处理返回数组的情况（虽然转换单张图片通常只返回一个 Blob）
      const blob = Array.isArray(convertedBlob) ? convertedBlob[0] : convertedBlob;

      const newName = file.name.replace(/\.[^/.]+$/, "") + ".jpg";
      return new File([blob], newName, { type: 'image/jpeg' });
    } catch (error) {
      console.error("HEIC conversion failed:", error);
      // 转换失败，返回原文件，让后端报错或尝试处理
      return file; 
    }
  }

  // --- 2. 已经是 JPEG 或 PNG，直接返回 ---
  if (file.type === 'image/jpeg' || file.type === 'image/png') {
    return file;
  }

  // --- 3. 处理 WebP / GIF 等浏览器原生支持但需要转格式的图片 ---
  // 使用 Canvas 绘制（主要是为了处理透明背景变黑的问题）
  return new Promise((resolve) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    
    img.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = img.width;
      canvas.height = img.height;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        URL.revokeObjectURL(url);
        resolve(file);
        return;
      }
      
      // 绘制白色背景
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, 0, 0);

      canvas.toBlob((blob) => {
        URL.revokeObjectURL(url);
        if (blob) {
          const newName = file.name.replace(/\.[^/.]+$/, "") + ".jpg";
          resolve(new File([blob], newName, { type: 'image/jpeg' }));
        } else {
          resolve(file);
        }
      }, 'image/jpeg', 0.9);
    };

    img.onerror = () => {
      URL.revokeObjectURL(url);
      resolve(file);
    };

    img.src = url;
  });
};