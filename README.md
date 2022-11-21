#### 目的
为了充分理解响应式框架的设计实现，以[zio-actors](https://github.com/zio/zio-actors)项目为蓝本，实现actor。

#### 设计实现
TODO

#### 对比Spark中Actor实现
[spark-rpc](https://github.com/changzhiwin/spark-rpc)这个是Spark内部对Actor原理的实现。

|  对比项    | zio-actor   | spark-rpc            |  
| --------- | ----------- |  ------              |
| 网络层     | zio-nio     | netty                |
| 队列       | ZIO#Queue   | java.util.LinkedList |
| 执行器     | ZIO#Fiber   | 线程池                |
| 异步       | ZIO#Promise | Future               |

