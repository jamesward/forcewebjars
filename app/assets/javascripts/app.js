window.name = "NG_DEFER_BOOTSTRAP!";

require([
  "angular",
  "ui-bootstrap-tpls",
  "css!bootstrap-css",
  "css!app-css"
], function(angular) {
  angular.module("myApp", ["ui.bootstrap"])
    .controller("AppController", function($scope, $http) {

      $scope.instanceUrl = window.instanceUrl;

      var httpConfig = {
        headers: {
          "X-ID-URL": window.idUrl,
          "X-ACCESS-TOKEN": window.accessToken,
          "X-REFRESH-TOKEN": window.refreshToken,
          "X-INSTANCE-URL": window.instanceUrl
        }
      };

      $scope.webJarsLoading = true;

      $scope.installWebJar = function(webJar, version) {
        $http
          .post("/org/webjars", {id: webJar.artifactId, version: version.number}, httpConfig)
          .success(function(data) {
            $scope.selectedInstallWebJar = null;
            $scope.selectedInstallWebJarVersion = null;
            getOrgWebJars();
          })
          .error(function(data) {
            console.log(data);
            // todo
          });
      };

      $scope.toggleWebJar = function(webJar) {
        if (webJar.files === undefined) {
          // fetch the files
          $http
            .get("https://webjars.herokuapp.com/listfiles/" + webJar.id + "/" + webJar.version)
            .success(function(data) {
              webJar.files = data;
            })
            .error(function(data) {
              console.log(data);
              // todo
            });
        }
      };


      // get user & org info
      $http
        .get("/org/userinfo", httpConfig)
        .success(function(data) {
          $scope.userOrgInfo = data;
        })
        .error(function(data) {
          console.log(data);
          // todo
        });

      // get All WebJars
      $http
        .get("https://webjars.herokuapp.com/all")
        .success(function(data) {
          var classicWebJars = data.filter(function(webJar) {
            return webJar.groupId == "org.webjars";
          });

          $scope.allWebJars = classicWebJars;
          $scope.webJarsLoading = false;
        })
        .error(function(data) {
          console.log(data);
          // todo
        });

      // get org WebJars
      function getOrgWebJars() {
        $http
          .get("/org/webjars", httpConfig)
          .success(function(data) {
            $scope.webJars = data.map(function(item) {
              var webJarDescription = JSON.parse(item.Description);
              webJarDescription.sfid = item.Id;
              webJarDescription.sfname = item.Name;
              return webJarDescription;
            });
          })
          .error(function(data) {
            console.log(data);
            // todo
          });
      }

      getOrgWebJars();

    });

  angular.element(document).ready(function() {
    angular.resumeBootstrap();
  });

});