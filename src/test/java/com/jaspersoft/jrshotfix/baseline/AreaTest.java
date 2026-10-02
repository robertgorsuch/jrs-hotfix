package com.jaspersoft.jrshotfix.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What the installer and buildomatic write for one site, as measured on 10.0.0 (open point 1). */
class AreaTest {

  @Test
  void should_know_the_files_written_with_this_sites_values() {
    for (String path :
        new String[] {
          "buildomatic/default_master.properties",
          "buildomatic/keystore.init.properties",
          "buildomatic/bin/js-import-export.sh",
          "buildomatic/install_resources/export/js-catalog/resources/public/Samples/Data_Sources/"
              + "FoodmartDataSource.xml"
        }) {
      assertThat(Area.INSTALLATION.siteFile(path)).as(path).isTrue();
      assertThat(Area.WEBAPP.siteFile(path)).as(path).isFalse();
    }
    // the vendor's catalog archives beside that directory are shipped by hotfixes
    assertThat(
            Area.INSTALLATION.siteFile(
                "buildomatic/install_resources/export/js-catalog-postgresql-pro.zip"))
        .isFalse();
    assertThat(Area.INSTALLATION.siteFile("buildomatic/js-ant.sh")).isFalse();
  }

  @Test
  void should_know_what_is_built_or_written_at_run_time() {
    assertThat(Area.INSTALLATION.generated("buildomatic/build_conf/default/js.jdbc.properties"))
        .isTrue();
    assertThat(Area.INSTALLATION.generated("buildomatic/logs/js-export.log")).isTrue();
    assertThat(Area.INSTALLATION.generated("buildomatic/conf_source/iePro/lib/iecp.jar")).isTrue();
    assertThat(Area.INSTALLATION.generated("buildomatic/conf_source/iePro/lib/other.jar"))
        .isFalse();
  }
}
