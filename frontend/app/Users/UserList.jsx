import React from 'react';
import SortableTable from 'react-sortable-table';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import {Link} from "react-router-dom";
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import axios from 'axios';
import CollectionSelector from './CollectionSelector.jsx';
import SizeInput from "../common/SizeInput.jsx";
import GenericDropdown from "../common/GenericDropdown.jsx";
import AutocompletingEditBox from "../common/AutocompletingEditBox.jsx";

class UserList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            usersList: [],
            loading: false,
            saving: false,
            collectionsList: [],
            lastError: null,
            knownDepartments: []
        };

        this.boolFieldChanged = this.boolFieldChanged.bind(this);
        this.loadUsers = this.loadUsers.bind(this);
        this.quotaChanged = this.quotaChanged.bind(this);
    }

    /**
     * updates the specific user profile in our state
     * @param newEntry
     */
    updateEntry(newEntry){
        this.setState({usersList: this.state.usersList
                .filter(entry=>entry.userEmail!==newEntry.userEmail)
                .concat(newEntry)
                .sort(UserList.sortFunc)
        })
    }

    boolFieldChanged(entry, fieldName, currentValue) {
        this.stringFieldChanged(entry, fieldName, currentValue ? "false" : "true")
    }

    stringFieldChanged(entry, fieldName, newString){
        const updateRq = {
            user: entry.userEmail,
            fieldName: fieldName,
            stringValue: newString,
            operation: "OP_OVERWRITE"
        };

        this.setState({saving: true}, ()=>axios.put("/api/user/update",updateRq).then(response=>{
            this.updateEntry(response.data.entry);
        }).catch(err=>{
            console.error(err);
            this.setState({saving: false, lastError: err});
        }))
    }

    performUpdate(updateRq){
        this.setState({saving: true}, ()=>axios.put("/api/user/update", updateRq).then(response=>{
            this.updateEntry(response.data.entry);
        }).catch(err=>{
            console.error(err);
            this.setState({saving: false, lastError: err});
        }))
    }

    userCollectionsUpdated(entry, newValue){
        const updateRq = {
            user: entry.userEmail,
            fieldName: "VISIBLE_COLLECTIONS",
            listValue: newValue,
            operation: "OP_OVERWRITE"
        };

        this.performUpdate(updateRq);
    }

    quotaChanged(entry, quotaField, newValue){
        const newValueString = (newValue / 1048576).toString();
        const updateRq = {
            user: entry.userEmail,
            fieldName: quotaField,
            stringValue: newValueString,
            operation: "OP_OVERWRITE"
        };

        this.performUpdate(updateRq);
    }

    mainBody(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;

        return <table style={{borderCollapse: "collapse", width: "100%"}}>
            <thead>
            <tr className="dashboardheader">
                <th className="dashboardheader">Email</th>
                <th className="dashboardheader">Production office</th>
                <th className="dashboardheader">Department</th>
                <th className="dashboardheader">Administrator</th>
                <th className="dashboardheader">Visible collections</th>
                <th className="dashboardheader">Allow all collections</th>
                <th className="dashboardheader">Quota limits</th>
            </tr>
            </thead>
            <tbody>
            {
                this.state.usersList.map((entry,idx)=><tr key={idx}>
                    <td>{entry.userEmail}</td>
                    <td><GenericDropdown valueList={["UK","US","Aus"]}
                                         onChange={ evt=>
                                             this.stringFieldChanged(entry, "PRODUCTION_OFFICE", evt.target.value)
                                         }
                                         value={entry.productionOffice}/>
                    </td>
                    <td><AutocompletingEditBox items={this.state.knownDepartments}
                                               initialValue={entry.department}
                                               newValueOkayed={newValue=>this.stringFieldChanged(entry,"DEPARTMENT", newValue)}
                                               />
                    </td>
                    <td><input type="checkbox" checked={entry.isAdmin} onChange={()=>this.boolFieldChanged(entry, "IS_ADMIN", entry.isAdmin)}/></td>
                    <td style={{textAlign: "left"}}>
                        <CollectionSelector collections={this.state.collectionsList}
                                            userSelected={entry.visibleCollections}
                                            selectionUpdated={newValue=>this.userCollectionsUpdated(entry, newValue)}
                                            disabled={entry.allCollectionsVisible}
                        /></td>
                    <td><input type="checkbox" checked={entry.allCollectionsVisible} onChange={()=>this.boolFieldChanged(entry, "ALL_COLLECTIONS", entry.allCollectionsVisible)}/></td>
                    <td>
                        { /* the multiply is because the server holds the restore quota value in Mb */}
                        <ul>
                            <li><p className="list-control-label">Single restore limit</p>
                                <SizeInput sizeInBytes={entry.perRestoreQuota ? (entry.perRestoreQuota*1048576) : 0}
                                           didUpdate={newValue=>this.quotaChanged(entry,"PER_RESTORE_QUOTA", newValue)}
                                           minimumMultiplier={1048576}
                                           />
                                <p className="information">This is the amount of data that a user can request from Glacier without requiring administrator approval. Set to zero to always require admin approval.</p>

                            </li>
                            <li style={{display: "none"}}><p className="list-control-label">Rolling 30-day restore limit</p>
                                <SizeInput sizeInBytes={entry.rollingRestoreQuota ? (entry.rollingRestoreQuota*1048576) : 0}
                                           didUpdate={newValue=>this.quotaChanged(entry,"ROLLING_QUOTA", newValue)}
                                           minimumMultiplier={1048576}
                                           />
                            </li>
                            <li style={
                                //{display: entry.isAdmin ? "list-item" : "none"}
                                {display: "none"}
                            }><p className="list-control-label">Admin's one-off authorisation limit:</p>
                                <SizeInput sizeInBytes={entry.adminAuthQuota ? (entry.adminAuthQuota*1048576) : 0}
                                           didUpdate={newValue=>this.quotaChanged(entry,"ADMIN_APPROVAL_QUOTA", newValue)}
                                           minimumMultiplier={1048576}
                                           />
                            </li>
                            <li style={
                                //{display: entry.isAdmin ? "list-item" : "none"}
                                {display: "none"}
                            }><p className="list-control-label">Admin's rolling 30-day authorisation limit:</p>
                                <SizeInput sizeInBytes={entry.adminRollingAuthQuota ? (entry.adminRollingAuthQuota*1048576) : 0}
                                           didUpdate={newValue=>this.quotaChanged(entry,"ADMIN_ROLLING_APPROVAL_QUOTA", newValue)}
                                           minimumMultiplier={1048576}
                                />
                            </li>
                        </ul>
                    </td>
                </tr>)
            }
            </tbody>
        </table>
    }

    loadCollectionsList(){
        console.log("loadCollectionsList");
        return new Promise((resolve, reject)=>
            this.setState({loading: true}, ()=>axios.get("/api/scanTarget")
                .then(result=>{
                    this.setState({collectionsList: result.data.entries.map(entry=>entry.bucketName), loading: false}, ()=>resolve())
                }).catch(err=>{
                    this.setState({lastError: err, loading: false});
                    window.setTimeout(this.loadCollectionsList, 3000);    //retry after 3 seconds
                })
            )
        );
    }

    static sortFunc(a,b){
        return a.userEmail.localeCompare(b.userEmail);
    }

    loadUsers(){
        console.log("loadUsers");
        return new Promise((resolve, reject)=>
            this.setState({loading: true}, ()=>axios.get("/api/user")
                .then(result=>{
                    this.setState({
                        usersList: result.data.entries.sort(UserList.sortFunc),
                        //dedupe the list of known departments by using Set
                        knownDepartments: [...new Set(result.data.entries.map(entry=>entry.department).filter(dept=>dept!==null))],
                        loading: false}, ()=>resolve());

                }).catch(err=>{
                    this.setState({lastError: err, loading: false});
                    window.setTimeout(this.loadUsers, 3000)
                })
            )
        );
    }

    componentWillMount(){
        this.loadCollectionsList().then(this.loadUsers);
    }

    render(){
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            {this.mainBody()}
        </div>

    }
}

export default UserList;