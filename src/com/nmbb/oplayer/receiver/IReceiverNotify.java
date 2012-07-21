package com.nmbb.oplayer.receiver;

public interface IReceiverNotify {

	/**
	 * 
	 * @param flag 0 开始扫描 1 正在扫描 2 扫描完成
	 */
	public void receiver(int flag);
}
