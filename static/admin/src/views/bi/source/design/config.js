const config = {
}

const JDBCOptions = () => {
  return {
    url: 'jdbc:mysql://127.0.0.1:3306/db_name?characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true',
    username: 'root',
    password: ''
  }
}

const MongoDBOptions = () => {
  return { hosts: '127.0.0.1:27017', database: 'admin', username: '', password: '' }
}

export default Object.assign(config, {
  widgetTransientMap: null,
  widgetByType (type) {
    if (this.widgetTransientMap === null) {
      const map = {}
      this.widgets.forEach(widget => {
        widget.children.forEach(item => {
          map[item.type] = item
        })
      })
      this.widgetTransientMap = map
    }
    return this.widgetTransientMap[type]
  },
  widgetDefaults (type) {
    return this.widgetByType(type).options()
  },
  widgets: [{
    key: 'server',
    name: '服务',
    children: [
      { type: 'MongoDB', label: 'MongoDB', options: MongoDBOptions, property: () => import('./MongoDBProperty') },
      { type: 'MySQL', label: 'MySQL', options: JDBCOptions, property: () => import('./JDBCProperty') }
    ]
  }, {
    key: 'file',
    name: '文件',
    children: []
  }, {
    key: 'dag',
    name: '计算',
    children: []
  }]
})
