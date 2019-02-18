import React from 'react';
import SortableTable from 'react-sortable-table';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import {Link} from "react-router-dom";
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import axios from 'axios';
import CollectionSelector from './CollectionSelector.jsx';
import SizeInput from "../common/SizeInput.jsx";

class UserList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            usersList: [],
            loading: false,
            saving: false,
            collectionsList: [],
            lastError: null
        };

        this.boolFieldChanged = this.boolFieldChanged.bind(this);
        this.loadUsers = this.loadUsers.bind(this);
        this.perRestoreQuotaChanged = this.perRestoreQuotaChanged.bind(this);
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

    boolFieldChanged(entry, fieldName, currentValue){
        const updateRq = {
            user: entry.userEmail,
            fieldName: fieldName,
            stringValue: currentValue ? "false" : "true",
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

    perRestoreQuotaChanged(entry, newValue){
        const newValueString = (newValue / 1048576).toString();
        const updateRq = {
            user: entry.userEmail,
            fieldName: "PER_RESTORE_QUOTA",
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
                <th className="dashboardheader">Administrator</th>
                <th className="dashboardheader">Visible collections</th>
                <th className="dashboardheader">Allow all collections</th>
                <th className="dashboardheader">Per restore size limit</th>
            </tr>
            </thead>
            <tbody>
            {
                this.state.usersList.map((entry,idx)=><tr key={idx}>
                    <td>{entry.userEmail}</td>
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
                        <SizeInput sizeInBytes={entry.perRestoreQuota ? (entry.perRestoreQuota*1048576) : 0} didUpdate={newValue=>this.perRestoreQuotaChanged(entry, newValue)}/>
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
        return a.toString().localeCompare(b);
    }

    loadUsers(){
        console.log("loadUsers");
        return new Promise((resolve, reject)=>
            this.setState({loading: true}, ()=>axios.get("/api/user")
                .then(result=>{
                    this.setState({usersList: result.data.entries.sort(UserList.sortFunc), loading: false}, ()=>resolve())
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