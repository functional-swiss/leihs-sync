Leihs MS-Graph (Azure-AD) Sync
===============================

This project contains code and a service to sync users and groups from
Microsoft Azure-AD via the Microsoft Graph API into a leihs instance.

License and Copyright
---------------------

Copyright © Functional LLC Switzerland
Thomas.Schank@functional.swiss

The contents of this repository may be used under the terms of either the

* GNU Lesser General Public License (LGPL) v3 https://www.gnu.org/licenses/lgpl

or the

* Eclipse Public License (EPL) http://www.eclipse.org/org/documents/epl-v10.php


Set up Access to the Microsoft Graph API
----------------------------------------

See also the section
[Accessing MS-Graph and Testing the Parameters](#accessing-ms-graph-and-testing-the-parameters)
way below.

The sync finally needs:

* `tennant_id`
* `application_id`
* `client_secret`

Follow through the following three steps to get those.


### Register Application


> Application permissions are used by apps that run without a signed-in user
  present; for example, apps that run as background services or daemons.
  Application permissions can only be consented by an administrator.

* https://docs.microsoft.com/en-us/graph/auth-register-app-v2
* https://docs.microsoft.com/en-us/azure/active-directory/develop/v2-permissions-and-consent?WT.mc_id=Portal-Microsoft_AAD_RegisteredApps#next-steps


### Grant Permissions

Add Microsoft Graph Permission

* `GroupRead.All`
* `GroupMember.Read.All`
* `User.Read.All`

Possibly an administrator needs to give consent.


### Create a `client_secret`

* https://aad.portal.azure.com/#blade/Microsoft_AAD_IAM/StartboardApplicationsMenuBlade/AllApps
* https://portal.azure.com/#blade/Microsoft_AAD_RegisteredApps/ApplicationMenuBlade/Credentials/appId/{APP_ID}/isMSAApp/

The `secret` is shown only once.


Deploy
------

Setting the `leihs_sync_name` is required.  You might want to specify the path
to the `config_file` explicitly.  See `deploy/roles/deploy/defaults/main.yml`
for the default value.

```
export INVENTORY_DIR=/Users/thomas/Programming/LEIHS/leihs_v5/zhdk-inventory
export HOSTS_FILE=test-hosts
export LEIHS_SYNC_NAME='zapi'
${INVENTORY_DIR}/leihs/deploy/bin/ansible-playbook -i ${INVENTORY_DIR}/${HOSTS_FILE} deploy/deploy_play.yml  -v  -e "leihs_sync_name=${LEIHS_SYNC_NAME}"
```


Development
-----------

# Repl

    (require '[funswiss.leihs-ms-connect.main :refer :all] :reload-all)
    (-main "-c" "config.yml" "run" "-l" "log.edn")

# Build

    ./bin/clj-uberjar

The build requires a recent Clojure version to be present in the path.



Accessing MS-Graph and Testing the Parameters
---------------------------------------------

### Get a Token

https://docs.microsoft.com/en-us/graph/auth-v2-service#4-get-an-access-token

> In the OAuth 2.0 client credentials grant flow, you use the Application ID
  and Application Secret values that you saved when you registered your app to
  request an access token directly from the Microsoft identity platform /token
  endpoint.


```
export CLIENT_ID='...'
export CLIENT_SECRET='...'
export TENNANT_ID='...'
```

```
curl -i -X POST -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=${CLIENT_ID}&scope=https%3A%2F%2Fgraph.microsoft.com%2F.default&client_secret=${CLIENT_SECRET}&grant_type=client_credentials" \
  "https://login.microsoftonline.com/${TENNANT_ID}/oauth2/v2.0/token"
```



```
export TOKEN=$(curl -q -X POST -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=${CLIENT_ID}&scope=https%3A%2F%2Fgraph.microsoft.com%2F.default&client_secret=${CLIENT_SECRET}&grant_type=client_credentials" \
  "https://login.microsoftonline.com/${TENNANT_ID}/oauth2/v2.0/token" \
  | jq '.access_token' --raw-output)

```


### Test Access
```

export TOKEN='...'

curl -i 'https://graph.microsoft.com/v1.0/users?$top=2' \
  -H "Authorization: Bearer ${TOKEN}"

export GROUP_ID='...'

curl "https://graph.microsoft.com/v1.0/groups/${GROUP_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  | json_pp


curl "https://graph.microsoft.com/v1.0/groups/${GROUP_ID}/transitiveMembers/microsoft.graph.user" \
  -H "Authorization: Bearer ${TOKEN}" | json_pp


export USER_ID='...'

curl "https://graph.microsoft.com/v1.0/users/${USER_ID}" \
  -H "Authorization: Bearer ${TOKEN}" | json_pp


curl "https://graph.windows.net/mytenant.onmicrosoft.com/users/${USER_ID}?api-version=1.6" \
  -H "Authorization: Bearer ${TOKEN}" | json_pp

```




