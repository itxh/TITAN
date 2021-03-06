/*
 * Copyright (C) 2015-2020 yunjiweidian
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */
package com.yunji.titan.manager.impl.service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.yunji.titan.manager.bo.ActionPerformanceBO;
import com.yunji.titan.manager.common.CharsetTypeEnum;
import com.yunji.titan.manager.common.ContentTypeEnum;
import com.yunji.titan.manager.common.SceneStatusEnum;
import com.yunji.titan.manager.entity.Link;
import com.yunji.titan.manager.entity.LinkVariable;
import com.yunji.titan.manager.entity.Scene;
import com.yunji.titan.manager.service.LinkService;
import com.yunji.titan.manager.service.OperateService;
import com.yunji.titan.manager.service.SceneService;
import com.yunji.titan.manager.utils.CommonTypeUtil;
import com.yunji.titan.task.facade.TaskFacade;
import com.yunji.titan.utils.ContentType;
import com.yunji.titan.utils.LinkBean;
import com.yunji.titan.utils.LinkScope;
import com.yunji.titan.utils.ProtocolType;
import com.yunji.titan.utils.RequestType;
import com.yunji.titan.utils.TaskIssuedBean;

/**
 * @desc 操作Service接口实现类
 *
 * @author liuliang
 *
 */
@Service
public class OperateServiceImpl implements OperateService {

	private Logger logger = LoggerFactory.getLogger(OperateServiceImpl.class);

	/**
	 * 场景服务
	 */
	@Resource
	private SceneService sceneService;
	/**
	 * 链路服务
	 */
	@Resource
	private LinkService linkService;
	/**
	 * 压测任务下发接口
	 */
	@Resource
	private TaskFacade taskFacade;

	/**
	 * @desc 获取执行压测操作参数
	 *
	 * @author liuliang
	 *
	 * @param sceneId
	 *            场景ID
	 * @return Map<String,Object>
	 * @throws Exception
	 */
	@Override
	public ActionPerformanceBO getActionParamer(long sceneId) throws Exception {
		// 1、查询场景信息
		Scene scene = sceneService.getScene(sceneId);
		String sceneName = scene.getSceneName();
		int concurrentUsersSize = scene.getConcurrentUser();
		int initConcurrentUsersSize = scene.getConcurrentStart();
		long taskSize = scene.getTotalRequest();
		int agentSize = scene.getUseAgent();
		int expectThroughput = scene.getExpectTps();
		String oldids = scene.getLinkRelation();
		String ids=oldids.replace("[", "").replace("]", "");
		int hour = scene.getDurationHour();
		int min = scene.getDurationMin();
		int sec = scene.getDurationSec();
		// 2、查询链路信息
		List<Link> linkList = linkService.getLinkListByIds(ids);
		// 3、拼装参数
		String cidstr=this.rmchar(ids);
		String[] cids=cidstr.split(",");
		Map<String, ProtocolType> protocolTypes = new HashMap<String, ProtocolType>(16);
		Map<String, RequestType> requestTypes = new HashMap<String, RequestType>(16);
		Map<String, ContentType> contentTypes = new HashMap<String, ContentType>(16);
		Map<String, String> charsets = new HashMap<String, String>(16);
		Map<String, String> successExpression = new HashMap<String, String>(16);
		Map<String, File> params = new HashMap<String, File>(16);
		Map<String, String> idUrls = new HashMap<String, String>(16);
		List<String> urls = new ArrayList<String>();
		for(int i=0;i<cids.length;i++){
			Link link=findLink(cids[i],linkList);
			protocolTypes.put(link.getStresstestUrl(), CommonTypeUtil.getProtocolType(link.getProtocolType()));
			requestTypes.put(link.getStresstestUrl(), CommonTypeUtil.getRequestType(link.getRequestType()));
			contentTypes.put(link.getStresstestUrl(), ContentTypeEnum.getContentType(link.getContentType()));
			charsets.put(link.getStresstestUrl(), CharsetTypeEnum.getValue(link.getCharsetType()));
			successExpression.put(link.getStresstestUrl(), link.getSuccessExpression());
			
			if (StringUtils.isBlank(link.getTestfilePath())) {
				params.put(link.getStresstestUrl(), null);
			} else {
				params.put(link.getStresstestUrl(), new File(link.getTestfilePath()));
			}
			idUrls.put(cids[i], link.getStresstestUrl());
			if(!urls.contains(link.getStresstestUrl())){
				urls.add(link.getStresstestUrl());
			}
		}
		// 4、查询链路变量定义信息
		List<LinkVariable> linkVarList = linkService.getLinkVariableListByIds(ids);
		Map<String, List<String>> vars = new HashMap(16);
		for (LinkVariable var : linkVarList) {
			List<String> list=vars.get(var.getStresstestUrl());
			if(list==null){
				list=new ArrayList();
				list.add(var.getVarName()+","+var.getVarExpression());
				vars.put(var.getStresstestUrl(), list);
			}else{
				list.add(var.getVarName()+","+var.getVarExpression());
			}
		}
		// 计算持续时间
		int continuedTime = hour * 60 * 60 + min * 60 + sec;
		TimeUnit timeUnit = null;
		if (0 < continuedTime) {
			timeUnit = TimeUnit.SECONDS;
		}
		List<LinkBean> links=new ArrayList();
		
		String[] uol="".equals(scene.getUserOneloopLink().trim())?new String[]{}: scene.getUserOneloopLink().split(",");
		for(int i=0;i<uol.length;i++){
			LinkBean lb=new LinkBean();
			lb.setLinkId(Long.parseLong(uol[i]));
			lb.getLinkScope().add(LinkScope.USER_ONELOOP);
			links.add(lb);
		}
		
		String[] sol="".equals(scene.getSceneOneloopLink().trim())?new String[]{}: scene.getSceneOneloopLink().split(",");
		for(int h=0;h<sol.length;h++){
			Long id=Long.parseLong(sol[h]);
			LinkBean lb=links.stream().filter(
						(LinkBean b) -> b.getLinkId().equals(id)
					).findFirst().orElseGet(() -> this.newLink(id,links));
			lb.getLinkScope().add(LinkScope.SCENE_ONELOOP);
		}
		
		String[] pnl="".equals(scene.getParamNonrepeatLink().trim())?new String[]{}: scene.getParamNonrepeatLink().split(",");
		for(int g=0;g<pnl.length;g++){
			Long id=Long.parseLong(pnl[g]);
			LinkBean lb = links.stream().filter(
						(LinkBean b) -> b.getLinkId().equals(id)
					).findFirst().orElseGet(() -> this.newLink(id,links));
			lb.getLinkScope().add(LinkScope.PARAM_NONREPEAT);
		}

		// 4、返回
		ActionPerformanceBO actionPerformanceBO = new ActionPerformanceBO();
		actionPerformanceBO.setSceneId(sceneId);
		actionPerformanceBO.setSceneName(sceneName);
		actionPerformanceBO.setInitConcurrentUsersSize(initConcurrentUsersSize);
		actionPerformanceBO.setConcurrentUsersSize(concurrentUsersSize);
		actionPerformanceBO.setTaskSize(taskSize);
		
		actionPerformanceBO.setLinks(links);

		actionPerformanceBO.setAgentSize(agentSize);
		actionPerformanceBO.setExpectThroughput(expectThroughput);
		actionPerformanceBO.setContinuedTime(continuedTime);
		actionPerformanceBO.setTimeUnit(timeUnit);
		actionPerformanceBO.setProtocolTypes(protocolTypes);

		actionPerformanceBO.setRequestTypes(requestTypes);
		actionPerformanceBO.setUrls(urls);
		actionPerformanceBO.setParams(params);
		actionPerformanceBO.setContentTypes(contentTypes);
		actionPerformanceBO.setCharsets(charsets);
		actionPerformanceBO.setVariables(vars);
		actionPerformanceBO.setSuccessExpression(successExpression);
		actionPerformanceBO.setContainLinkIds(oldids);
		actionPerformanceBO.setIdUrls(idUrls);

		return actionPerformanceBO;
	}
	
	private LinkBean newLink(Long id ,List<LinkBean> links ){
		LinkBean b=new LinkBean();
		b.setLinkId(id);
		links.add(b);
		return b;
	}

	@Override
	public void doAutomaticTask(long sceneId, int executeNum) throws Exception {
		// 1、
		ActionPerformanceBO ap = this.getActionParamer(sceneId);
		executeNum = executeNum < 1 ? 1 : executeNum;
		// 2、
		if (null != ap) {
			for (int i = 0; i < executeNum; i++) {
				//状态校验
				while(true){
					Scene scene = sceneService.getScene(sceneId);
					if((null != scene) && (0 == scene.getSceneStatus())){
						break;
					}
					Thread.sleep(5000);
				}
				//执行
				TaskIssuedBean taskIssuedBean = new TaskIssuedBean();
				this.copyBeanProperties(ap, taskIssuedBean);
				com.yunji.titan.utils.Result<?> operateResult = taskFacade.startPerformanceTest(taskIssuedBean);
				if(operateResult.isSuccess()) {
					int updateResult = sceneService.updateSceneStatus(sceneId, SceneStatusEnum.STARTING.value);
					if(0 == updateResult){
						logger.warn("自动压测,更新DB失败,sceneId:{}", sceneId);
					}
				}else {
					logger.warn("自动压测,失败,sceneId:{},operateResult:{}", sceneId,operateResult.toString());
				}
			}
		} else {
			logger.info("自动压测失败,查询ActionPerformanceBO为空,sceneId:{}", sceneId);
		}
	}
	
	/**
	 * @desc 压测参数bean转换
	 *
	 * @author liuliang
	 *
	 * @param ap
	 * @param tb
	 */
	@Override
	public void copyBeanProperties(ActionPerformanceBO ap, TaskIssuedBean tb){
		tb.setTaskId(null);
		tb.setZnode(null);
		tb.setSenceId(ap.getSceneId().intValue());
		tb.setSenceName(ap.getSceneName());
		tb.setInitConcurrentUsersSize(ap.getInitConcurrentUsersSize());
		
		tb.setConcurrentUsersSize(ap.getConcurrentUsersSize());
		tb.setTaskSize(ap.getTaskSize());
		tb.setAgentSize(ap.getAgentSize());
		tb.setExpectThroughput(ap.getExpectThroughput());
		tb.setContinuedTime(ap.getContinuedTime());
		
		tb.setUrls(ap.getUrls());
		tb.setRequestTypes(ap.getRequestTypes());
		tb.setProtocolTypes(ap.getProtocolTypes());
		tb.setParams(ap.getParams());
		tb.setContentTypes(ap.getContentTypes());
		
		tb.setCharsets(ap.getCharsets());
		tb.setVariables(ap.getVariables());
		tb.setSuccessExpression(ap.getSuccessExpression());
		tb.setTimeUnit(ap.getTimeUnit());
		tb.setContainLinkIds(ap.getContainLinkIds());
		tb.setIdUrls(ap.getIdUrls());
		tb.setLinks(ap.getLinks());
		
	}
	
	private static Link findLink(String id, List<Link> linkList){
		for (Link link : linkList) {
			if(id.equals(link.getLinkId().toString())){
				return link;
			}
		}
		return null;
	}

	private String rmchar(String str){
		String c1=""+str.charAt(0);
		String c2=""+str.charAt(str.length()-1);
		if(",".equals(c1)){
			str=str.substring(1);
		}
		if(",".equals(c2)){
			str=str.substring(0,str.length()-1);
		}
		return str;
	}

	   public static void main(String[] args){

			String[] uol="6".split(",");
			for(int i=0;i<uol.length;i++){
				System.out.println("--"+uol[i]);
			}
//		   ActionPerformanceBO ap=new ActionPerformanceBO();
//		   List<Link> listlink=new ArrayList();
//		   Link l=new Link();
//		   l.setCharsetType(10);
//		   l.setLinkId(10l);
//		   listlink.add(l);
//		   
//		   l=new Link();
//		   l.setCharsetType(20);
//		   l.setLinkId(20l);
//		   listlink.add(l);
//		   ap.setLinks(listlink);
//		   
//			List<LinkBean> links=new ArrayList();
//			for(Link k:ap.getLinks()){
//				LinkBean b=new LinkBean();
//				BeanUtils.copyProperties(k, b);
//				links.add(b);
//			}
//			System.out.println(links.size());
	   }
}
