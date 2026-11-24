import BasicLayout from "@console/layouts/BasicLayout.vue";
import { IconBookRead } from "@halo-dev/components";
import { definePlugin } from "@halo-dev/ui-shared";
import { markRaw } from "vue";

export default definePlugin({
  routes: [
    {
      path: "/zhihu-archives",
      name: "ZhihuArchivesRoot",
      component: BasicLayout,
      meta: {
        title: "core.zhihu_archive.title",
        searchable: true,
        permissions: ["system:zhihu-archives:view"],
        menu: {
          name: "core.sidebar.menu.items.zhihu_archives",
          group: "content",
          icon: markRaw(IconBookRead),
          priority: 2,
        },
      },
      children: [
        {
          path: "",
          name: "ZhihuArchives",
          component: () => import("./ZhihuArchiveList.vue"),
        },
      ],
    },
  ],
});
