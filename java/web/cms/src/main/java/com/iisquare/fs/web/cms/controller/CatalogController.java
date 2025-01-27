package com.iisquare.fs.web.cms.controller;

import com.iisquare.fs.base.core.util.ApiUtil;
import com.iisquare.fs.base.core.util.DPUtil;
import com.iisquare.fs.base.core.util.ValidateUtil;
import com.iisquare.fs.web.cms.entity.Catalog;
import com.iisquare.fs.web.cms.service.CatalogService;
import com.iisquare.fs.web.core.rbac.DefaultRbacService;
import com.iisquare.fs.web.core.rbac.Permission;
import com.iisquare.fs.web.core.rbac.PermitControllerBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/catalog")
public class CatalogController extends PermitControllerBase {

    @Autowired
    private DefaultRbacService rbacService;
    @Autowired
    private CatalogService catalogService;

    @RequestMapping("/tree")
    @Permission("")
    public String treeAction(@RequestBody Map<?, ?> param) {
        return ApiUtil.echoResult(0, null, catalogService.tree(param, DPUtil.buildMap(
                "withUserInfo", true, "withStatusText", true
        )));
    }

    @RequestMapping("/list")
    @Permission("")
    public String listAction(@RequestBody Map<?, ?> param) {
        Map<?, ?> result = catalogService.search(param, DPUtil.buildMap(
            "withUserInfo", true, "withStatusText", true, "withParentInfo", true
        ));
        return ApiUtil.echoResult(0, null, result);
    }

    @RequestMapping("/save")
    @Permission({"add", "modify"})
    public String saveAction(@RequestBody Map<?, ?> param, HttpServletRequest request) {
        Integer id = ValidateUtil.filterInteger(param.get("id"), true, 1, null, 0);
        String name = DPUtil.trim(DPUtil.parseString(param.get("name")));
        if(DPUtil.empty(name)) return ApiUtil.echoResult(1001, "名称异常", name);
        int sort = DPUtil.parseInt(param.get("sort"));
        int status = DPUtil.parseInt(param.get("status"));
        if(!catalogService.status("default").containsKey(status)) return ApiUtil.echoResult(1002, "状态异常", status);
        String description = DPUtil.parseString(param.get("description"));
        int parentId = DPUtil.parseInt(param.get("parentId"));
        if(parentId < 0) {
            return ApiUtil.echoResult(1003, "上级节点异常", name);
        } else if(parentId > 0) {
            Catalog parent = catalogService.info(parentId);
            if(null == parent || !catalogService.status("default").containsKey(parent.getStatus())) {
                return ApiUtil.echoResult(1004, "上级节点不存在或已删除", name);
            }
        }
        String cover = DPUtil.trim(DPUtil.parseString(param.get("cover")));
        String title = DPUtil.trim(DPUtil.parseString(param.get("title")));
        String keyword = DPUtil.trim(DPUtil.parseString(param.get("keyword")));
        Catalog info = null;
        if(id > 0) {
            if(!rbacService.hasPermit(request, "modify")) return ApiUtil.echoResult(9403, null, null);
            info = catalogService.info(id);
            if(null == info) return ApiUtil.echoResult(404, null, id);
        } else {
            if(!rbacService.hasPermit(request, "add")) return ApiUtil.echoResult(9403, null, null);
            info = new Catalog();
        }
        info.setName(name);
        info.setParentId(parentId);
        info.setCover(cover);
        info.setTitle(title);
        info.setKeyword(keyword);
        info.setSort(sort);
        info.setStatus(status);
        info.setDescription(description);
        info = catalogService.save(info, rbacService.uid(request));
        return ApiUtil.echoResult(null == info ? 500 : 0, null, info);
    }

    @RequestMapping("/delete")
    @Permission
    public String deleteAction(@RequestBody Map<?, ?> param, HttpServletRequest request) {
        Integer id = ValidateUtil.filterInteger(param.get("id"), true, 1, null, 0);
        Map<String, Object> result = catalogService.delete(id, rbacService.uid(request));
        return ApiUtil.echoResult(result);
    }

    @RequestMapping("/config")
    @Permission("")
    public String configAction(ModelMap model) {
        model.put("status", catalogService.status("default"));
        return ApiUtil.echoResult(0, null, model);
    }

}
