import React from 'react';
import axios from 'axios';
import {createStyles, Paper, Snackbar, withStyles, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import AdminContainer from "../admin/AdminContainer";
import {makeUserListColumns} from "./UserListContent";
import {DataGrid} from "@material-ui/data-grid";
import MuiAlert from "@material-ui/lab/Alert";
import ErrorViewComponent from "../common/ErrorViewComponent";
import {Helmet} from "react-helmet";

const styles = (theme)=> Object.assign(createStyles({
    tableContainer: {
        height: "90vh"
    }
}), baseStyles);

class UserList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            usersList: [],
            loading: false,
            saveCompleted: false,
            showingAlert: false,
            collectionsList: [],
            lastError: null,
            knownDepartments: [],
            open: false,
            userToDelete: ""
        };

        this.handleClose = this.handleClose.bind(this);
        this.boolFieldChanged = this.boolFieldChanged.bind(this);
        this.stringFieldChanged = this.stringFieldChanged.bind(this);
        this.loadUsers = this.loadUsers.bind(this);
        this.userCollectionsUpdated = this.userCollectionsUpdated.bind(this);
        this.quotaChanged = this.quotaChanged.bind(this);
        this.deleteClicked = this.deleteClicked.bind(this);
        this.handleCloseDialogue = this.handleCloseDialogue.bind(this);
        this.handleDelete = this.handleDelete.bind(this);
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
                .map((user,idx)=>Object.assign({id: idx}, user))
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

        axios.put("/api/user/update",updateRq).then(response=>{
            this.updateEntry(response.data.entry);
            this.setState({saveCompleted: true, showingAlert: true});
        }).catch(err=>{
            console.error(err);
            this.setState({lastError: err, showingAlert: true});
        })
    }

    performUpdate(updateRq){
        axios.put("/api/user/update", updateRq).then(response=>{
            this.updateEntry(response.data.entry);
            this.setState({saveCompleted: true, showingAlert: true});
        }).catch(err=>{
            console.error(err);
            this.setState({showingAlert: true, lastError: err});
        });
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
        return new Promise((resolve, reject)=>
            this.setState({loading: true}, ()=>axios.get("/api/user")
                .then(result=>{
                    this.setState({
                        usersList: result.data.entries
                            .sort(UserList.sortFunc)
                            .map((user,idx)=>Object.assign({id: idx}, user)),   //give each UserProfile an id parameter for MUI
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

    componentDidMount(){
        this.loadCollectionsList().then(this.loadUsers);
    }

    handleClose() {
        this.setState({showingAlert: false},()=>this.setState({saveCompleted: false, lastError: null}));
    }

    deleteClicked(entry){
        this.setState({open: true, userToDelete: entry.userEmail});
    }

    handleCloseDialogue() {
        this.setState({open: false});
    };

    handleDelete() {
        this.setState({open: false});
        const deleteRq = {
            user: this.state.userToDelete
        };
        axios.put("/api/user/delete",deleteRq).then(response=>{
        }).then(res => {
            this.loadUsers();
        }).catch(err=>{
            console.error(err);
        })
    };

    render(){
        const columns = makeUserListColumns(
            this.state.knownDepartments,
            this.state.collectionsList,
            this.stringFieldChanged,
            this.boolFieldChanged,
            this.userCollectionsUpdated,
            this.quotaChanged,
            this.deleteClicked
        );

        const targetRowHeight = (this.state.collectionsList && this.state.collectionsList.length>0) ?
            this.state.collectionsList.length * 40 : 52;

        return <>
            <Helmet>
                <title>Users - ArchiveHunter</title>
            </Helmet>
            <Snackbar open={this.state.showingAlert}
                      autoHideDuration={8000}
                      onClose={this.handleClose}>
                <>
                {
                    this.state.lastError ?
                        <MuiAlert elevation={6} severity="error">
                            {ErrorViewComponent.formatError(this.state.lastError)}
                        </MuiAlert> : null
                }
                {
                    this.state.saveCompleted ?
                        <MuiAlert elevation={6} severity="success">
                            Saved
                        </MuiAlert> : null
                }
                </>
            </Snackbar>
            <AdminContainer {...this.props}>
                <Paper elevation={3} className={this.props.classes.tableContainer}>
                    <DataGrid  columns={columns}
                               rows={this.state.usersList}
                               rowHeight={targetRowHeight}
                               rowsPerPageOptions={[10,20,50,100]}
                    />
                </Paper>
            </AdminContainer>
            <Dialog
                open={this.state.open}
                onClose={this.handleCloseDialogue}
                aria-labelledby="alert-dialog-title"
                aria-describedby="alert-dialog-description"
            >
                <DialogTitle id="alert-dialog-title">
                    {"Delete User?"}
                </DialogTitle>
                <DialogContent>
                    <DialogContentText id="alert-dialog-description">
                        Are you sure you want to delete the user {this.state.userToDelete}?
                    </DialogContentText>
                </DialogContent>
                <DialogActions>
                    <Button onClick={this.handleCloseDialogue}>No</Button>
                    <Button onClick={this.handleDelete} autoFocus>
                        Yes
                    </Button>
                </DialogActions>
            </Dialog>
            </>
    }
}

export default withStyles(styles)(UserList);