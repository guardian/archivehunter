import React, {ChangeEvent} from "react";
import {ColDef} from "@material-ui/data-grid";
import GenericDropdown from "../common/GenericDropdown";
import Autocomplete from "@material-ui/lab/Autocomplete";
import {makeStyles, Switch, TextField, IconButton} from "@material-ui/core";
import CollectionSelector from "./CollectionSelector";
import {UserProfile, UserProfileRow} from "../types";
import RestoreLimitComponent from "./RestoreLimitComponent";
import DepartmentSelector from "./DepartmentSelector";
import { default as DeleteIcon } from '@material-ui/icons/Delete'

function makeUserListColumns(
    knownDepartments: string[],
    collectionsList: string[],
    stringFieldChanged: (entry:UserProfileRow, fieldName:string, newString:string)=>void,
    boolFieldChanged: (entry:UserProfileRow, fieldName:string, currentValue:boolean)=>void,
    userCollectionsUpdated: (entry:UserProfileRow, newValues:string[])=>void,
    quotaChanged: (entry:UserProfileRow, fieldName:string, newValue:number)=>void,
    deleteClicked: (entry:UserProfileRow)=>void
):ColDef[] {
    return [
        {
            field: "userEmail",
            headerName: "Email",
            width: 200,
        },
        {
            field: "productionOffice",
            headerName: "Office",
            width: 150,
            renderCell: (params) => <GenericDropdown valueList={["UK","US","Aus"]}
                                                     onChange={(evt)=>stringFieldChanged(params.row as UserProfileRow, "PRODUCTION_OFFICE", evt.target.value)}
                                                     value={(params.value ?? "UK") as string}/>
        },
        {
            field: "department",
            headerName: "Department",
            width: 400,
            renderCell: (params) => <DepartmentSelector knownDepartments={knownDepartments}
                                                        value={params.value as string}
                                                        onChange={
                                                            (evt, newValue)=>
                                                                stringFieldChanged(params.row as UserProfileRow,"DEPARTMENT", newValue ?? "")
                                                        }
            />

        },
        {
            field: "isAdmin",
            headerName: "Admin",
            width: 100,
            renderCell: (params) => <Switch checked={params.value as boolean}
                                            onClick={()=>boolFieldChanged(params.row as UserProfileRow,"IS_ADMIN", params.value as boolean)}/>
        },
        {
            field: "visibleCollections",
            headerName: "Visible Collections",
            width: 300,
            renderCell: (params) => <CollectionSelector collections={collectionsList}
                                                        userSelected={params.value as string[]}
                                                        selectionUpdated={(newValues:string[])=>userCollectionsUpdated(params.row as UserProfileRow, newValues)}/>
        },
        {
            field: "allCollectionsVisible",
            headerName: "All collections visible",
            width: 100,
            renderCell: (params)=><Switch checked={params.value as boolean}
                                          onClick={()=>boolFieldChanged(params.row as UserProfileRow, "ALL_COLLECTIONS", params.value as boolean)}/>
        },
        {
            field: "restoreLimits",
            headerName: "Restore limits",
            width:600,
            renderCell: (params)=><RestoreLimitComponent row={params.row as UserProfileRow} quotaChanged={quotaChanged}/>
        },
        {
            field: "delete",
            headerName: "Delete",
            width:100,
            renderCell: (params)=><IconButton onClick={()=>deleteClicked(params.row as UserProfileRow)}><DeleteIcon /></IconButton>
        }
    ]
}

export {makeUserListColumns}