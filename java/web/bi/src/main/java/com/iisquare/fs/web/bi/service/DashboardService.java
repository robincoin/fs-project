package com.iisquare.fs.web.bi.service;

import com.iisquare.fs.base.core.util.DPUtil;
import com.iisquare.fs.base.core.util.ValidateUtil;
import com.iisquare.fs.base.web.mvc.ServiceBase;
import com.iisquare.fs.web.bi.dao.DashboardDao;
import com.iisquare.fs.web.bi.entity.Dashboard;
import com.iisquare.fs.web.core.rbac.DefaultRbacService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import javax.persistence.criteria.Predicate;
import java.util.*;

@Service
public class DashboardService extends ServiceBase {

    @Autowired
    private DashboardDao dashboardDao;
    @Autowired
    private DefaultRbacService rbacService;

    public Map<?, ?> search(Map<?, ?> param, Map<?, ?> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        int page = ValidateUtil.filterInteger(param.get("page"), true, 1, null, 1);
        int pageSize = ValidateUtil.filterInteger(param.get("pageSize"), true, 1, 500, 15);
        Page<Dashboard> data = dashboardDao.findAll((Specification<Dashboard>) (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.notEqual(root.get("status"), -1));
            String name = DPUtil.trim(DPUtil.parseString(param.get("name")));
            if(!DPUtil.empty(name)) {
                predicates.add(cb.like(root.get("name"), "%" + name + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        }, PageRequest.of(page - 1, pageSize, Sort.by(new Sort.Order(Sort.Direction.DESC, "sort"))));
        List<?> rows = data.getContent();
        if(!DPUtil.empty(config.get("withUserInfo"))) {
            rbacService.fillUserInfo(rows, "createdUid", "updatedUid");
        }
        if(!DPUtil.empty(config.get("withStatusText"))) {
            DPUtil.fillValues(rows, new String[]{"status"}, new String[]{"statusText"}, status("full"));
        }
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", data.getTotalElements());
        result.put("rows", rows);
        return result;
    }

    public Map<?, ?> status(String level) {
        Map<Integer, String> status = new LinkedHashMap<>();
        status.put(1, "启用");
        status.put(2, "禁用");
        switch (level) {
            case "default":
                break;
            case "full":
                status.put(-1, "已删除");
                break;
            default:
                return null;
        }
        return status;
    }

    public Dashboard info(Integer id) {
        if(null == id || id < 1) return null;
        Optional<Dashboard> info = dashboardDao.findById(id);
        return info.isPresent() ? info.get() : null;
    }

    public Dashboard save(Dashboard info, int uid) {
        long time = System.currentTimeMillis();
        info.setUpdatedTime(time);
        info.setUpdatedUid(uid);
        if(null == info.getId()) {
            info.setCreatedTime(time);
            info.setCreatedUid(uid);
        }
        return dashboardDao.save(info);
    }

    public boolean delete(List<Integer> ids, int uid) {
        if(null == ids || ids.size() < 1) return false;
        List<Dashboard> list = dashboardDao.findAllById(ids);
        long time = System.currentTimeMillis();
        for (Dashboard item : list) {
            item.setStatus(-1);
            item.setUpdatedTime(time);
            item.setUpdatedUid(uid);
        }
        dashboardDao.saveAll(list);
        return true;
    }

}
