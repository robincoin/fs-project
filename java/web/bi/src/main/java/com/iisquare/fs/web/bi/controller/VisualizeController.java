package com.iisquare.fs.web.bi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.iisquare.fs.base.core.util.ApiUtil;
import com.iisquare.fs.base.core.util.DPUtil;
import com.iisquare.fs.base.core.util.ValidateUtil;
import com.iisquare.fs.web.bi.entity.Visualize;
import com.iisquare.fs.web.bi.service.VisualizeService;
import com.iisquare.fs.web.core.rbac.DefaultRbacService;
import com.iisquare.fs.web.core.rbac.Permission;
import com.iisquare.fs.web.core.rbac.PermitControllerBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/visualize")
public class VisualizeController extends PermitControllerBase {

    @Autowired
    private VisualizeService visualizeService;
    @Autowired
    private DefaultRbacService rbacService;

    @RequestMapping("/search")
    @Permission
    public String searchAction(@RequestBody Map<?, ?> param) {
        Visualize info = null;
        if (param.containsKey("id")) {
            Integer id = ValidateUtil.filterInteger(param.get("id"), true, 1, null, 0);
            info = visualizeService.info(id);
            if (null == info || 1 != info.getStatus()) {
                return ApiUtil.echoResult(1404, "当前报表暂不可用", id);
            }
        }
        Integer datasetId = null == info ? DPUtil.parseInt(param.get("datasetId")) : info.getDatasetId();
        JsonNode preview = null == info ? DPUtil.toJSON(param.get("preview")) : DPUtil.parseJSON(info.getContent());
        JsonNode levels = DPUtil.toJSON(param.get("levels"));
        Map<String, Object> result = visualizeService.search(datasetId, preview, levels);
        return ApiUtil.echoResult(result);
    }

    @RequestMapping("/info")
    @Permission("")
    public String infoAction(@RequestBody Map<?, ?> param) {
        Integer id = ValidateUtil.filterInteger(param.get("id"), true, 1, null, 0);
        Visualize info = visualizeService.info(id);
        return ApiUtil.echoResult(null == info ? 404 : 0, null, info);
    }

    @RequestMapping("/list")
    @Permission("")
    public String listAction(@RequestBody Map<?, ?> param) {
        Map<?, ?> result = visualizeService.search(param, DPUtil.buildMap(
            "withUserInfo", true, "withDatasetInfo", true, "withStatusText", true
        ));
        return ApiUtil.echoResult(0, null, result);
    }

    @RequestMapping("/save")
    @Permission({"add", "modify"})
    public String saveAction(@RequestBody Map<?, ?> param, HttpServletRequest request) {
        Integer id = ValidateUtil.filterInteger(param.get("id"), true, 1, null, 0);
        String name = DPUtil.trim(DPUtil.parseString(param.get("name")));
        int sort = DPUtil.parseInt(param.get("sort"));
        int status = DPUtil.parseInt(param.get("status"));
        int datasetId = DPUtil.parseInt(param.get("datasetId"));
        String type = DPUtil.parseString(param.get("type"));
        String content = DPUtil.parseString(param.get("content"));
        String description = DPUtil.parseString(param.get("description"));
        if(param.containsKey("name") || id < 1) {
            if(DPUtil.empty(name)) return ApiUtil.echoResult(1001, "名称异常", name);
        }
        if(param.containsKey("status")) {
            if(!visualizeService.status("default").containsKey(status)) return ApiUtil.echoResult(1004, "状态参数异常", status);
        }
        Visualize info;
        if(id > 0) {
            if(!rbacService.hasPermit(request, "modify")) return ApiUtil.echoResult(9403, null, null);
            info = visualizeService.info(id);
            if(null == info) return ApiUtil.echoResult(404, null, id);
        } else {
            if(!rbacService.hasPermit(request, "add")) return ApiUtil.echoResult(9403, null, null);
            info = new Visualize();
        }
        if(param.containsKey("datasetId") || null == info.getId()) info.setDatasetId(datasetId);
        if(param.containsKey("name") || null == info.getId()) info.setName(name);
        if(param.containsKey("type") || null == info.getId()) info.setType(type);
        if(param.containsKey("content") || null == info.getId()) info.setContent(content);
        if(param.containsKey("description") || null == info.getId()) info.setDescription(description);
        if(param.containsKey("sort") || null == info.getId()) info.setSort(sort);
        if(param.containsKey("status") || null == info.getId()) info.setStatus(status);
        info = visualizeService.save(info, rbacService.uid(request));
        return ApiUtil.echoResult(null == info ? 500 : 0, null, info);
    }

    @RequestMapping("/delete")
    @Permission
    public String deleteAction(@RequestBody Map<?, ?> param, HttpServletRequest request) {
        List<Integer> ids = null;
        if(param.get("ids") instanceof List) {
            ids = DPUtil.parseIntList(param.get("ids"));
        } else {
            ids = Arrays.asList(DPUtil.parseInt(param.get("ids")));
        }
        boolean result = visualizeService.delete(ids, rbacService.uid(request));
        return ApiUtil.echoResult(result ? 0 : 500, null, result);
    }

    @RequestMapping("/config")
    @Permission("")
    public String configAction(ModelMap model) {
        model.put("status", visualizeService.status("default"));
        return ApiUtil.echoResult(0, null, model);
    }

}
