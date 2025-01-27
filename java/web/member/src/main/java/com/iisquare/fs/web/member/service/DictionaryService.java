package com.iisquare.fs.web.member.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iisquare.fs.base.core.util.DPUtil;
import com.iisquare.fs.base.core.util.ReflectUtil;
import com.iisquare.fs.base.core.util.ValidateUtil;
import com.iisquare.fs.base.jpa.util.JPAUtil;
import com.iisquare.fs.base.web.mvc.ServiceBase;
import com.iisquare.fs.web.member.dao.DictionaryDao;
import com.iisquare.fs.web.member.entity.Dictionary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.util.*;

@Service
public class DictionaryService extends ServiceBase {

    @Autowired
    private DictionaryDao dictionaryDao;
    @Autowired
    private UserService userService;

    public Map<?, ?> status(String level) {
        Map<Integer, String> status = new LinkedHashMap<>();
        status.put(1, "启用");
        status.put(2, "关闭");
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

    public Dictionary info(Integer id) {
        if(null == id || id < 1) return null;
        Optional<Dictionary> info = dictionaryDao.findById(id);
        return info.isPresent() ? info.get() : null;
    }

    public Dictionary save(Dictionary info, int uid) {
        long time = System.currentTimeMillis();
        Dictionary parent = info(info.getParentId());
        if (null == parent) {
            info.setFullName(info.getName());
        } else {
            info.setFullName(parent.getFullName() + ":" + info.getName());
        }
        info.setUpdatedTime(time);
        info.setUpdatedUid(uid);
        if(null == info.getId()) {
            info.setCreatedTime(time);
            info.setCreatedUid(uid);
        }
        return dictionaryDao.save(info);
    }

    public <T> List<T> fillInfo(List<T> list, String ...properties) {
        Set<Integer> ids = DPUtil.values(list, Integer.class, properties);
        if(ids.size() < 1) return list;
        Map<Integer, Dictionary> data = DPUtil.list2map(dictionaryDao.findAllById(ids), Integer.class, "id");
        return DPUtil.fillValues(list, properties, "Name", DPUtil.values(data, String.class, "name"));
    }

    public ObjectNode available(boolean withChildren) {
        List<Dictionary> list = this.tree(DPUtil.buildMap("status", 1), DPUtil.buildMap());
        return this.available(list, withChildren);
    }

    public ObjectNode findAvailable(ObjectNode available, String path, boolean withChildren) {
        String[] paths = DPUtil.explode("\\.", path);
        for (String key : paths) {
            JsonNode node = available.at("/" + key + "/children");
            if (!node.isObject()) return DPUtil.objectNode();
            available = (ObjectNode) node;
        }
        if (withChildren) return available;
        ObjectNode result = DPUtil.objectNode();
        Iterator<Map.Entry<String, JsonNode>> iterator = available.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            ObjectNode node = result.putObject(entry.getKey());
            JsonNode item = entry.getValue();
            node.put("label", item.get("label").asText());
            node.put("value", item.get("value").asText());
        }
        return result;
    }

    public ObjectNode available(List<Dictionary> list, boolean withChildren) {
        ObjectNode result = DPUtil.objectNode();
        if (null == list) return result;
        for (Dictionary dictionary : list) {
            ObjectNode node = result.putObject(dictionary.getContent());
            node.put("label", dictionary.getName());
            node.put("value", dictionary.getContent());
            if (!withChildren) continue;
            node.replace("children", available(dictionary.getChildren(), withChildren));
        }
        return result;
    }

    public List<Dictionary> tree(Map<?, ?> param, Map<?, ?> args) {
        List<Dictionary> data = dictionaryDao.findAll((Specification) (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            int status = DPUtil.parseInt(param.get("status"));
            if(!"".equals(DPUtil.parseString(param.get("status")))) {
                predicates.add(cb.equal(root.get("status"), status));
            } else {
                predicates.add(cb.notEqual(root.get("status"), -1));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        }, Sort.by(new Sort.Order(Sort.Direction.DESC, "sort")));
        if(!DPUtil.empty(args.get("withUserInfo"))) {
            userService.fillInfo(data, "createdUid", "updatedUid");
        }
        if(!DPUtil.empty(args.get("withStatusText"))) {
            DPUtil.fillValues(data, new String[]{"status"}, new String[]{"statusText"}, status("full"));
        }
        return DPUtil.formatRelation(data, Dictionary.class, "parentId", 0, "id", "children");
    }

    public Map<?, ?> search(Map<?, ?> param, Map<?, ?> args) {
        Map<String, Object> result = new LinkedHashMap<>();
        int page = ValidateUtil.filterInteger(param.get("page"), true, 1, null, 1);
        int pageSize = ValidateUtil.filterInteger(param.get("pageSize"), true, 1, 500, 15);
        Sort sort = JPAUtil.sort(DPUtil.parseString(param.get("sort")), Arrays.asList("id", "sort"));
        if (null == sort) sort = Sort.by(Sort.Order.desc("sort"));
        Page<?> data = dictionaryDao.findAll(new Specification() {
            @Override
            public Predicate toPredicate(Root root, CriteriaQuery query, CriteriaBuilder cb) {
                List<Predicate> predicates = new ArrayList<>();
                int id = DPUtil.parseInt(param.get("id"));
                if(id > 0) predicates.add(cb.equal(root.get("id"), id));
                int status = DPUtil.parseInt(param.get("status"));
                if(!"".equals(DPUtil.parseString(param.get("status")))) {
                    predicates.add(cb.equal(root.get("status"), status));
                } else {
                    predicates.add(cb.notEqual(root.get("status"), -1));
                }
                String name = DPUtil.trim(DPUtil.parseString(param.get("name")));
                if(!DPUtil.empty(name)) {
                    predicates.add(cb.like(root.get("name"), "%" + name + "%"));
                }
                String fullName = DPUtil.trim(DPUtil.parseString(param.get("fullName")));
                if(!DPUtil.empty(fullName)) {
                    predicates.add(cb.like(root.get("fullName"), "%" + fullName + "%"));
                }
                int parentId = DPUtil.parseInt(param.get("parentId"));
                if(!"".equals(DPUtil.parseString(param.get("parentId")))) {
                    predicates.add(cb.equal(root.get("parentId"), parentId));
                }
                String content = DPUtil.trim(DPUtil.parseString(param.get("content")));
                if(!DPUtil.empty(content)) {
                    predicates.add(cb.equal(root.get("content"), content));
                }
                return cb.and(predicates.toArray(new Predicate[0]));
            }
        }, PageRequest.of(page - 1, pageSize, sort));
        List<?> rows = data.getContent();
        if(!DPUtil.empty(args.get("withUserInfo"))) {
            userService.fillInfo(rows, "createdUid", "updatedUid");
        }
        if(!DPUtil.empty(args.get("withStatusText"))) {
            DPUtil.fillValues(rows, new String[]{"status"}, new String[]{"statusText"}, status("full"));
        }
        if(!DPUtil.empty(args.get("withParentInfo"))) {
            this.fillInfo(rows, "parentId");
        }
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", data.getTotalElements());
        result.put("rows", rows);
        return result;
    }

    public boolean remove(List<Integer> ids) {
        if(null == ids || ids.size() < 1) return false;
        dictionaryDao.deleteInBatch(dictionaryDao.findAllById(ids));
        return true;
    }

    public boolean delete(List<Integer> ids, int uid) {
        if(null == ids || ids.size() < 1) return false;
        List<Dictionary> list = dictionaryDao.findAllById(ids);
        long time = System.currentTimeMillis();
        for (Dictionary item : list) {
            item.setStatus(-1);
            item.setUpdatedTime(time);
            item.setUpdatedUid(uid);
        }
        dictionaryDao.saveAll(list);
        return true;
    }
}
