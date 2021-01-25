import React from "react";
import clsx from 'clsx';
import {RouteComponentProps} from "react-router";
import BreadcrumbComponent from "../common/BreadcrumbComponent";
import {
    AppBar,
    Divider,
    Drawer,
    Icon,
    IconButton, Link,
    List,
    ListItem, ListItemIcon, ListItemText,
    makeStyles,
    Toolbar, Typography,
    useTheme
} from "@material-ui/core";
import {MenuIcon} from "@material-ui/data-grid";
import {
    ChevronLeft,
    ChevronRight,
    Healing,
    PeopleAlt,
    Storage,
    TrackChanges,
    Work,
    WorkSharp
} from "@material-ui/icons";
import {Link as RouterLink} from "react-router-dom";
import ErrorCatcher from "./ErrorCatcher";

/* mostly taken from the "Persistent Drawer" example at https://material-ui.com/components/drawers/ */
const drawerWidth = 220;
const useStyles = makeStyles((theme)=>({
    appBar: {
        transition: theme.transitions.create(['margin', 'width'], {
            easing: theme.transitions.easing.sharp,
            duration: theme.transitions.duration.leavingScreen,
        }),
    },
    appBarShift: {
        width: `calc(100% - ${drawerWidth}px)`,
        transition: theme.transitions.create(['margin', 'width'], {
            easing: theme.transitions.easing.easeOut,
            duration: theme.transitions.duration.enteringScreen,
        }),
        marginRight: drawerWidth,
    },
    title: {
        flexGrow: 1,
    },
    hide: {
        display: 'none',
    },
    drawer: {
        width: drawerWidth,
        flexShrink: 0,
    },
    linkText: {
        textAlign: "right",
        color: "#AAAAAA"
    },
    drawerPaper: {
        width: drawerWidth,
        top: "auto",
        borderTop: "1px solid rgba(255,255,255,0.12)",
        borderBottom:  "1px solid rgba(255,255,255,0.12)",
        marginTop: "1em",
        height: "auto"
    },
    drawerHeader: {
        display: 'flex',
        alignItems: 'center',
        padding: theme.spacing(0, 1),
        // necessary for content to be below app bar
        ...theme.mixins.toolbar,
        justifyContent: 'flex-start',
    },
    content: {
        flexGrow: 1,
        padding: theme.spacing(3),
        transition: theme.transitions.create('margin', {
            easing: theme.transitions.easing.sharp,
            duration: theme.transitions.duration.leavingScreen,
        }),
        marginRight: 0,
        marginLeft: 0,
    },
    contentShift: {
        transition: theme.transitions.create('margin', {
            easing: theme.transitions.easing.easeOut,
            duration: theme.transitions.duration.enteringScreen,
        }),
        marginRight: 0,
        marginLeft: drawerWidth
    },
    menuButton: {

    },
    right: {
        textAlign: "right"
    }
}));

const menuItems = [
    {
        uri: "/admin/scanTargets",
        icon: <TrackChanges/>,
        name: "Scan Targets"
    },
    {
        uri: "/admin/jobs",
        icon: <Work/>,
        name: "Jobs"
    },
    {
        uri: "/admin/users",
        icon: <PeopleAlt/>,
        name: "Users",
    },
    {
        uri: "/admin/proxyFramework",
        icon: <Storage/>,
        name: "Proxying Framework"
    },
    {
        uri: "/admin/proxyHealth",
        icon: <Healing/>,
        name: "Proxying Health Indicator"
    }
]

/**
 * AdminContainer is a container component that renders a side-drawer with the Admin menu around any child
 * component that is passed into it
 * @param props
 * @constructor
 */
const AdminContainer:React.FC<RouteComponentProps> = (props) => {
    const classes = useStyles();
    const theme = useTheme();
    const [open, setOpen] = React.useState(true);

    const handleDrawerOpen = ()=>setOpen(true);
    const handleDrawerClose = ()=>setOpen(false);
    
    return (
        <>
            <IconButton
                color="inherit"
                aria-label="open drawer"
                onClick={handleDrawerOpen}
                edge="start"
                className={clsx(classes.menuButton, open && classes.hide)}
                >
                <MenuIcon/>
            </IconButton>
            {
                props.location.pathname.endsWith("/admin") ? null : <BreadcrumbComponent path={props.location.pathname}/>
            }
            <Drawer variant="persistent"
                    anchor="left"
                    open={open}
                    classes={{
                paper: classes.drawerPaper
            }}>
                <div className={classes.drawerHeader}>
                    <IconButton onClick={handleDrawerClose}>
                        {theme.direction === 'ltr' ? <ChevronLeft/> : <ChevronRight/>}
                    </IconButton>
                    <Typography variant="h6" className={classes.right}>Admin</Typography>
                </div>
                <Divider/>
                <List>
                    {
                        menuItems.map((itm, idx)=>(
                            <ListItem button key={idx}>
                                {itm.icon ? <ListItemIcon>{itm.icon}</ListItemIcon> : null}
                                <ListItemText className={classes.linkText}>
                                    <Link component={RouterLink} to={itm.uri}>{itm.name}</Link>
                                </ListItemText>
                            </ListItem>
                        ))
                    }
                    <Divider/>
                    <ListItem button key="abt">
                        <ListItemText className={classes.linkText}>
                            <Link component={RouterLink} to="/admin/about" >About</Link>
                        </ListItemText>
                    </ListItem>
                </List>
            </Drawer>
            <main
                className={clsx(classes.content, {
                    [classes.contentShift]: open
                })}
                >
                <ErrorCatcher>{props.children}</ErrorCatcher>
            </main>
        </>
    )
}

export default AdminContainer;