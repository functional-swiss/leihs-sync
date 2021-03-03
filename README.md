Leihs MS-Office Sync
====================

This project contains a service to sync users and groups from Microsoft Office
into a leihs instance.


Access to the Microsoft Graph API
---------------------------------

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
export CLIENT_SECET='...'
export TENNANT_ID='...'
curl -X POST -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=${CLIENT_ID}&scope=https%3A%2F%2Fgraph.microsoft.com%2F.default&client_secret=${CLIENT_SECRET}&grant_type=client_credentials" \
  "https://login.microsoftonline.com/${TENNANT_ID}/oauth2/v2.0/token"
```


### Access The `users` endpoint with the Token

```
export TOKEN='...'
curl -i 'https://graph.microsoft.com/v1.0/users?$top=2' \
  -H "Authorization: Bearer ${TOKEN}"
```



DEV
===

# Repl

    (require '[funswiss.leihs-ms-connect.main :refer :all] :reload-all)
    (-main "-c" "config.yml" "run" "-l" "log.edn")

